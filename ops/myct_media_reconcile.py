"""Explicit, local-only Media reconciliation transaction. Never mutates media/RPC.

Injected `core` is myct-media. Plans and provenance are private owner inputs;
receipts expose counts/hashes only. All catalog writers must respect project locks.
"""
from collections import Counter
from contextlib import contextmanager, ExitStack
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import time
import uuid

LIMIT = 16 * 1024 * 1024
TTL = 600
HEX = re.compile(r'[0-9a-f]{64}')
TERMINAL = {'succeeded', 'failed', 'cancelled'}
CONFLICT = {'pending', 'uncertain', 'starting', 'running', 'cancelling', 'archiving',
            'restoring', 'recovering', 'relinking', 'releasing', 'verifying', 'in_progress'}


class Error(Exception):
    def __init__(self, code, operation=None):
        self.code, self.operation = code, operation
        super().__init__(code + (': ' + operation if operation else ''))


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, allow_nan=False).encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def document(raw):
    value = json.loads(raw)
    if not isinstance(value, dict): raise Error('INVALID_INPUT')
    return value


def metadata(s):
    return [s.st_dev, s.st_ino, s.st_mode, s.st_uid, s.st_nlink,
            s.st_size, s.st_mtime_ns, s.st_ctime_ns]


def check_dir(fd, private=False):
    s = os.fstat(fd)
    if not stat.S_ISDIR(s.st_mode) or s.st_uid != os.getuid() or s.st_mode & (0o077 if private else 0o022):
        raise Error('UNSAFE_DIRECTORY')


@contextmanager
def parent(path, create=False, private=False):
    """Pin every ancestor below home with O_NOFOLLOW; never traverse a symlink."""
    home = Path.home()
    if not path.is_absolute() or not path.is_relative_to(home) or '..' in path.parts or path == home:
        raise Error('UNSAFE_PATH')
    fd = os.open(home, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        check_dir(fd)
        for part in path.relative_to(home).parts[:-1]:
            if create:
                try:
                    os.mkdir(part, 0o700, dir_fd=fd)
                    os.fsync(fd)
                except FileExistsError:
                    pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd); fd = child
            check_dir(fd)
        check_dir(fd, private)
        yield fd
    finally:
        os.close(fd)


def read_at(fd, name):
    file = os.open(name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=fd)
    try:
        before = os.fstat(file)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or
                before.st_mode & 0o077 or before.st_nlink != 1 or before.st_size > LIMIT):
            raise Error('UNSAFE_PRIVATE_FILE')
        parts, size = [], 0
        while chunk := os.read(file, min(65536, LIMIT + 1 - size)):
            parts.append(chunk); size += len(chunk)
            if size > LIMIT: raise Error('INPUT_LIMIT')
        if metadata(before) != metadata(os.fstat(file)) or metadata(before) != metadata(os.stat(name, dir_fd=fd, follow_symlinks=False)):
            raise Error('INPUT_CHANGED')
        return b''.join(parts), metadata(before)
    finally:
        os.close(file)


def read_private(path, missing=False, private_parent=True):
    try:
        with parent(path, private=private_parent) as fd:
            return read_at(fd, path.name)
    except FileNotFoundError:
        if missing: return None, None
        raise Error('MISSING_INPUT') from None


def write_exclusive(fd, name, raw):
    file = os.open(name, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600, dir_fd=fd)
    try:
        view = memoryview(raw)
        while view:
            n = os.write(file, view)
            if n <= 0: raise Error('SHORT_WRITE')
            view = view[n:]
        os.fsync(file)
    finally:
        os.close(file)
    os.fsync(fd)


def atomic(fd, name, raw):
    temporary = '.reconcile-' + uuid.uuid4().hex
    try:
        write_exclusive(fd, temporary, raw)
        os.replace(temporary, name, src_dir_fd=fd, dst_dir_fd=fd)
        os.fsync(fd)
    finally:
        try: os.unlink(temporary, dir_fd=fd)
        except FileNotFoundError: pass


def root():
    return Path.home() / '.local/state/myct/media-reconcile/operations'


def catalogs():
    home = Path.home() / '.local/share/myct'
    return [home / 'archive/catalog.json', home / 'archive-torrents/catalog.json']


def environment():
    """Read journals directly, never invoke cloud.rows() (which can repair/write)."""
    home = Path.home() / '.local/share/myct'
    result = {'catalogs': [], 'jobs': []}
    for path in catalogs():
        raw, _ = read_private(path, missing=True)
        result['catalogs'].append(digest(raw) if raw is not None else None)
        if raw is None: continue
        doc = document(raw)
        if not isinstance(doc.get('entries'), dict): raise Error('INVALID_CATALOG')
        for row in doc['entries'].values():
            if not isinstance(row, dict) or row.get('state') not in {'verified', 'unverified', 'failed'}:
                raise Error('CATALOG_STATE_UNCERTAIN')
            if any(isinstance(row.get(k), dict) and row[k].get('state') in CONFLICT
                   for k in ('release', 'restore', 'recovery', 'last_restore')):
                raise Error('CATALOG_STATE_UNCERTAIN')
    try:
        with parent(home / 'cloud-jobs/.inspect', private=True) as fd:
            for name in sorted(os.listdir(fd)):
                if not name.endswith('.json'): continue
                if not re.fullmatch('[0-9a-f]{32}.json', name): raise Error('INVALID_JOB')
                raw, _ = read_at(fd, name); job = document(raw)
                if job.get('id') != name[:-5] or job.get('state') not in TERMINAL:
                    raise Error('CONFLICTING_JOB')
                result['jobs'].append([name[:-5], digest(raw)])
    except FileNotFoundError:
        pass
    return result


def provenance(path, plan):
    raw, _ = read_private(path, private_parent=False)
    doc = document(raw)
    expected = {encoded(e['after']['filesystem']) for e in plan['entries'] if e['eligible']}
    if (doc.get('schema') != 1 or doc.get('owner_verified_origin') is not True or
            doc.get('historical_identity_missing') is not True or
            not isinstance(doc.get('evidence_reference'), str) or not 8 <= len(doc['evidence_reference'].strip()) <= 2048 or
            {encoded(x) for x in doc.get('filesystems', [])} != expected):
        raise Error('PROVENANCE_REQUIRED')
    return digest(raw)


def candidate_metadata(core, plan):
    """Stat only: pin safe ancestors, never read or hash media contents."""
    for entry in plan['entries']:
        if not entry['eligible']: continue
        evidence = entry['evidence']
        expected = evidence['source_stat']
        paths = [(Path(evidence['source']), expected['ctime_ns']),
                 (core.media_root() / core.safe_relative(entry['path']), evidence['library_ctime_ns'])]
        for path, ctime in paths:
            with parent(path) as directory:
                s = os.stat(path.name, dir_fd=directory, follow_symlinks=False)
                if not stat.S_ISREG(s.st_mode) or s.st_uid != os.getuid() or s.st_mode & 0o022:
                    raise Error('UNSAFE_MEDIA_METADATA')
                if (any(getattr(s, 'st_' + field) != expected[field]
                        for field in ('dev', 'ino', 'size', 'mtime_ns', 'nlink', 'uid')) or s.st_ctime_ns != ctime):
                    raise Error('MEDIA_METADATA_CHANGED')


def read_rows(core):
    try: return core.read_torrents(include_files=True)
    except Exception: raise Error('RPC_UNAVAILABLE') from None


def base_plan(core, rows, state):
    try: return core.reconciliation_plan(rows, state)
    except Exception: raise Error('PROOF_UNAVAILABLE') from None


def snapshot(core, proof):
    raw, meta = read_private(core.state_path())
    state = document(raw)
    if not isinstance(state.get('links'), dict) or not isinstance(state.get('ignored'), list): raise Error('INVALID_INDEX')
    rows = read_rows(core)
    plan = base_plan(core, rows, state)
    # Rates, peers and upload counters are not identity. Track all membership and
    # relevant torrent states/paths/metadata, including incomplete entries.
    fields = ('hash_string', 'download_dir', 'status', 'left_until_done', 'metadata_percent_complete', 'error', 'files')
    rpc = sorted([{k: row.get(k) for k in fields} for row in rows], key=lambda x: x['hash_string'])
    result = {'plan': plan, 'index_sha256': digest(raw), 'index_stat': meta, 'rpc': rpc,
              'environment': environment(), 'provenance_sha256': provenance(proof, plan)}
    candidate_metadata(core, plan)
    # Recollect identity/RPC/control files after metadata inspection as well.
    if base_plan(core, read_rows(core), state) != plan:
        raise Error('PLAN_CHANGED')
    if read_private(core.state_path())[0] != raw or environment() != result['environment']:
        raise Error('PLAN_CHANGED')
    return result, state, raw


def overview(plan):
    summary = dict(Counter(e['reason'] for e in plan['entries']))
    total, candidates = len(plan['entries']), plan['eligible']
    current, absent = summary.get('CURRENT', 0), summary.get('ABSENT', 0)
    filesystems = {encoded(e['after']['filesystem']) for e in plan['entries'] if 'after' in e}
    return {'summary': summary,
            'counts': {'examined': total, 'candidates': candidates, 'current': current,
                       'absent': absent, 'protected': total - candidates - current - absent},
            'filesystems': [document(raw) for raw in sorted(filesystems)]}


def preview(core, proof, now=None):
    snap, _, _ = snapshot(core, proof)
    if not snap['plan']['eligible']: raise Error('NO_CANDIDATES')
    issued = int(time.time() if now is None else now)
    result = {'schema': 3, 'application_supported': True, 'issued': issued, 'expires': issued + TTL,
              'nonce': uuid.uuid4().hex, 'snapshot': snap, **overview(snap['plan']),
              'updates': [x['path'] for x in snap['plan']['entries'] if x['eligible']]}
    result['id'] = digest(encoded(result))
    return result


def validate(plan, confirmation, legacy, now):
    if not isinstance(plan, dict) or plan.get('schema') != 3: raise Error('INVALID_PLAN')
    inner = plan['snapshot']['plan']
    if (set(plan) != {'schema', 'application_supported', 'issued', 'expires', 'nonce', 'snapshot', 'summary', 'updates', 'id', 'counts', 'filesystems'} or
            plan['application_supported'] is not True or
            not re.fullmatch('[0-9a-f]{32}', plan.get('nonce', '')) or
            plan['updates'] != [e['path'] for e in inner['entries'] if e['eligible']]):
        raise Error('INVALID_PLAN')
    if any(plan[k] != value for k, value in overview(inner).items()):
        raise Error('INVALID_PLAN')
    identity = plan.get('id', '')
    if (not HEX.fullmatch(identity) or identity != confirmation or
            identity != digest(encoded({k: v for k, v in plan.items() if k != 'id'}))):
        raise Error('CONFIRMATION')
    if (type(plan.get('issued')) is not int or type(plan.get('expires')) is not int or
            plan['expires'] != plan['issued'] + TTL or not plan['issued'] <= now < plan['expires']):
        raise Error('EXPIRED')
    if plan['snapshot']['plan']['legacy_requires_explicit_reanchor'] and legacy != identity:
        raise Error('LEGACY_CONFIRMATION')


@contextmanager
def locks(core, hashes):
    """Nonblocking: delivery -> file catalog -> torrent catalog -> naming -> Media."""
    home = Path.home() / '.local/share/myct'
    paths = [home / 'cloud-jobs/delivery.lock', *[p.with_name('catalog.lock') for p in catalogs()],
             *[home / 'torrent-names' / (key + '.lock') for key in sorted(hashes)],
             core.state_path().with_suffix('.lock')]
    with ExitStack() as stack:
        for path in paths:
            directory = stack.enter_context(parent(path, create=True, private=True))
            fd = os.open(path.name, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600, dir_fd=directory)
            stack.callback(os.close, fd)
            s = os.fstat(fd)
            if not stat.S_ISREG(s.st_mode) or s.st_uid != os.getuid() or s.st_nlink != 1 or s.st_mode & 0o077:
                raise Error('UNSAFE_LOCK')
            try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError: raise Error('BUSY') from None
        yield


def receipt(journal, state=None, duplicate=False):
    observed = state or journal['state']
    return {'ok': True, 'operation': journal['operation'], 'state': observed,
            'updated': journal['updated'] if observed == 'succeeded' else 0 if observed == 'not_applied' else None,
            'planned_updates': journal['updated'], 'before_sha256': journal['before_sha256'],
            'after_sha256': journal['after_sha256'], 'already_applied': duplicate,
            'recovered_missing_intent': journal.get('recovered_missing_intent', False)}


def journal_valid(journal, identity):
    if (journal.get('schema') != 1 or journal.get('operation') != identity or
            journal.get('state') not in {'preparing', 'prepared', 'committing', 'succeeded', 'not_applied', 'superseded'} or
            type(journal.get('updated')) is not int or not 1 <= journal['updated'] <= 2000 or
            not isinstance(journal.get('hashes'), list) or
            any(not isinstance(k, str) or not re.fullmatch('[0-9a-f]{40}', k) for k in journal['hashes']) or
            not all(isinstance(journal.get(k), str) and HEX.fullmatch(journal[k]) for k in ('before_sha256', 'after_sha256'))):
        raise Error('INVALID_OPERATION')


def pending_operations():
    try:
        with parent(root() / '.inspect', private=True) as directory:
            for name in os.listdir(directory):
                if not HEX.fullmatch(name): raise Error('INVALID_OPERATION')
                try: raw, _ = read_private(root() / name / 'transaction.json')
                except Error: raise Error('RECOVERY_NEEDED', name) from None
                journal = document(raw); journal_valid(journal, name)
                if journal['state'] not in {'succeeded', 'not_applied', 'superseded'}:
                    raise Error('RECOVERY_NEEDED', name)
    except FileNotFoundError:
        pass


def plan_hashes(plan):
    hashes = {r['hash_string'] for r in plan['snapshot']['rpc']}
    hashes.update(e['before']['id'][:40] for e in plan['snapshot']['plan']['entries'] if 'before' in e)
    if not all(re.fullmatch('[0-9a-f]{40}', k) for k in hashes): raise Error('INVALID_HASH')
    return hashes


def apply(core, plan, proof, confirmation, legacy=None):
    validate(plan, confirmation, legacy, int(time.time()))
    identity = plan['id']
    hashes = plan_hashes(plan)
    with locks(core, hashes):
        validate(plan, confirmation, legacy, int(time.time()))
        environment()  # No new work can be accepted while delivery.lock is held.
        old, _ = read_private(root() / identity / 'transaction.json', missing=True)
        if old is not None:
            journal = document(old)
            journal_valid(journal, identity)
            current, _ = read_private(core.state_path())
            if journal.get('state') == 'succeeded' and digest(current) == journal['after_sha256']:
                return receipt(journal, duplicate=True)
            raise Error('RECOVERY_NEEDED', identity)
        pending_operations()
        fresh, state, before = snapshot(core, proof)
        if fresh != plan['snapshot']: raise Error('PLAN_CHANGED')
        validate(plan, confirmation, legacy, int(time.time()))
        inner = fresh['plan']
        proposed = core.reconciled_state(state, inner, inner, inner['fingerprint'], accept_legacy=legacy == identity)
        after = encoded(proposed)
        if not inner['eligible'] or before == after: raise Error('NO_CANDIDATES')
        journal = {'schema': 1, 'operation': identity, 'state': 'preparing',
                   'before_sha256': digest(before), 'after_sha256': digest(after),
                   'updated': inner['eligible'], 'hashes': sorted(hashes)}
        operation = root() / identity
        with parent(root() / '.create', create=True, private=True) as operations:
            try: os.mkdir(identity, 0o700, dir_fd=operations)
            except FileExistsError: raise Error('OPERATION_EXISTS', identity) from None
            try: os.fsync(operations)
            except Exception: raise Error('RECOVERY_NEEDED', identity) from None
        with parent(operation / 'transaction.json', private=True) as directory:
            # The enclosing op directory must be new, never an old backup destination.
            if os.listdir(directory): raise Error('OPERATION_EXISTS', identity)
            try:
                atomic(directory, 'transaction.json', encoded(journal))
                write_exclusive(directory, 'state.before.json', before)
                if read_at(directory, 'state.before.json')[0] != before: raise Error('BACKUP_INVALID')
                journal['state'] = 'prepared'; atomic(directory, 'transaction.json', encoded(journal))
                with parent(core.state_path(), private=True) as catalog:
                    temp = '.reconcile-' + uuid.uuid4().hex
                    try:
                        write_exclusive(catalog, temp, after)
                        journal['state'] = 'committing'; atomic(directory, 'transaction.json', encoded(journal))
                        # Metadata/RPC/control files must still match after the
                        # durable backup and temporary catalog, before publication.
                        last, _, _ = snapshot(core, proof)
                        if last != fresh: raise Error('PLAN_CHANGED')
                        if read_at(catalog, core.state_path().name)[0] != before: raise Error('INDEX_CHANGED')
                        with parent(core.state_path(), private=True) as active:
                            if (os.fstat(active).st_dev, os.fstat(active).st_ino) != (os.fstat(catalog).st_dev, os.fstat(catalog).st_ino):
                                raise Error('DIRECTORY_CHANGED')
                        validate(plan, confirmation, legacy, int(time.time()))
                        os.replace(temp, core.state_path().name, src_dir_fd=catalog, dst_dir_fd=catalog)
                        os.fsync(catalog)
                        if read_at(catalog, core.state_path().name)[0] != after: raise Error('INDEX_CHANGED')
                    finally:
                        try: os.unlink(temp, dir_fd=catalog)
                        except FileNotFoundError: pass
                journal['state'] = 'succeeded'; atomic(directory, 'transaction.json', encoded(journal))
            except Exception:
                # Keep durable intent/backup. No rollback, no automatic retry.
                raise Error('RECOVERY_NEEDED', identity) from None
        return receipt(journal)


def recover(core, identity, plan=None):
    """Classify interrupted commit, fsync and publish receipt; NEVER restore state."""
    if not HEX.fullmatch(identity): raise Error('INVALID_OPERATION')
    path = root() / identity / 'transaction.json'
    raw, _ = read_private(path, missing=True)
    if raw is None:
        if plan is None: raise Error('MISSING_INTENT_REQUIRES_PLAN', identity)
        return recover_missing(core, identity, plan)
    journal = document(raw)
    journal_valid(journal, identity)
    hashes = journal['hashes']
    if not all(re.fullmatch('[0-9a-f]{40}', k) for k in hashes): raise Error('INVALID_HASH')
    with locks(core, hashes):
        environment()
        raw, _ = read_private(path); journal = document(raw)
        journal_valid(journal, identity)
        current, _ = read_private(core.state_path()); checksum = digest(current)
        if checksum == journal['after_sha256']:
            backup, _ = read_private(path.with_name('state.before.json'))
            if digest(backup) != journal['before_sha256']: raise Error('BACKUP_INVALID')
            with parent(core.state_path(), private=True) as fd: os.fsync(fd)
            state = 'succeeded'
        elif checksum == journal['before_sha256']:
            state = 'not_applied'
        else:
            state = 'superseded'  # A newer/different catalog is never overwritten.
        journal['state'] = state
        with parent(path, private=True) as fd: atomic(fd, path.name, encoded(journal))
        return receipt(journal)


def recover_missing(core, identity, plan):
    # Recovery is not consent to apply: expired plans can only classify an
    # unchanged baseline. Exact canonical ID and all structural checks still hold.
    validate(plan, identity, identity, plan['issued'])
    hashes = plan_hashes(plan)
    path = root() / identity / 'transaction.json'
    with locks(core, hashes):
        environment()
        with parent(path, private=True) as directory:
            if 'transaction.json' in os.listdir(directory): raise Error('OPERATION_CHANGED', identity)
            evidence = []
            for name in sorted(os.listdir(directory)):
                # Only unpublished atomic-journal temporaries are possible before
                # the first intent. Preserve and fingerprint each; never delete it.
                if not re.fullmatch(r'\.reconcile-[0-9a-f]{32}', name):
                    raise Error('UNCLASSIFIED_EVIDENCE', identity)
                evidence.append([name, digest(read_at(directory, name)[0])])
            current, _ = read_private(core.state_path())
            if digest(current) != plan['snapshot']['index_sha256']:
                raise Error('UNCLASSIFIED_CATALOG', identity)
            inner = plan['snapshot']['plan']
            proposed = core.reconciled_state(document(current), inner, inner, inner['fingerprint'], accept_legacy=True)
            journal = {'schema': 1, 'operation': identity, 'state': 'not_applied',
                       'before_sha256': digest(current), 'after_sha256': digest(encoded(proposed)),
                       'updated': inner['eligible'], 'hashes': sorted(hashes),
                       'recovered_missing_intent': True, 'preserved_evidence': evidence}
            journal_valid(journal, identity)
            if read_private(core.state_path())[0] != current: raise Error('INDEX_CHANGED')
            # Does not authorize a retry. Publish only a classification receipt;
            # the catalog, media, and orphaned evidence remain byte-for-byte intact.
            try:
                # The original mkdir may itself have failed its parent fsync.
                with parent(root() / '.recover', private=True) as operations:
                    os.fsync(operations)
                atomic(directory, path.name, encoded(journal))
            except Exception: raise Error('RECOVERY_NEEDED', identity) from None
            return receipt(journal)


def run(core, argv):
    try:
        if len(argv) == 3 and argv[:2] == ['reconcile-preview', '--provenance']:
            return preview(core, Path(argv[2]))
        if len(argv) == 3 and argv[:2] == ['reconcile-recover', '--operation']:
            return recover(core, argv[2])
        if len(argv) == 5 and argv[:2] == ['reconcile-recover', '--operation'] and argv[3] == '--plan':
            raw, _ = read_private(Path(argv[4]), private_parent=False)
            return recover(core, argv[2], document(raw))
        if argv and argv[0] == 'reconcile-apply' and len(argv) in (7, 9):
            options = dict(zip(argv[1::2], argv[2::2]))
            if len(options) != (len(argv) - 1) // 2 or set(options) not in ({'--plan', '--provenance', '--confirm'}, {'--plan', '--provenance', '--confirm', '--accept-legacy'}):
                raise Error('ARGUMENT')
            raw, _ = read_private(Path(options['--plan']), private_parent=False)
            return apply(core, document(raw), Path(options['--provenance']), options['--confirm'], options.get('--accept-legacy'))
        raise Error('ARGUMENT')
    except Error as error:
        raise core.MediaError('Reconciliação bloqueada: ' + str(error)) from None
    except Exception:
        raise core.MediaError('Reconciliação bloqueada: entrada, IO ou estado inválido; confira o recibo antes de repetir.') from None
