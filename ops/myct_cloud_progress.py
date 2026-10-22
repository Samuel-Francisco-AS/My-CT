"""Opt-in numeric telemetry, separate from authoritative catalogs and job receipts."""
import json
import math
import os
from pathlib import Path
import re
import stat
import tempfile
import time

PHASES = {'preparing', 'hashing', 'uploading', 'downloading', 'verifying', 'done', 'failed',
          'remote_check', 'local_inventory', 'local_read', 'library_inventory', 'library_read',
          'transmission_check', 'finalizing', 'pausing', 'removing_entry', 'unlinking'}


def number(value):
    return type(value) in (int, float) and 0 <= value <= 2**53 - 1 and math.isfinite(value)


def read_progress(root, identity):
    try:
        if not re.fullmatch('[0-9a-f]{32}', identity):
            return None
        path = root / (identity + '.progress')
        # Inspect and read the same descriptor. Atomic replacement may unlink this
        # already-open private snapshot (nlink=0), which remains a valid sample.
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'r') as stream:
            info = os.fstat(stream.fileno())
            if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                    or info.st_mode & 0o077 or info.st_nlink > 1 or info.st_size > 4096):
                return None
            raw = stream.read(4097)
            if len(raw) > 4096:
                return None
            row = json.loads(raw)
        if row.get('phase') not in PHASES:
            return None
        fields = ('bytes_done', 'bytes_total', 'percent', 'speed_bps', 'eta_seconds', 'updated_at',
                  'current_file_index', 'total_files')
        clean = {'phase': row['phase']}
        for key in fields:
            value = row.get(key)
            if value is not None and not number(value):
                return None
            clean[key] = value
        metric = row.get('metric')
        if metric is not None:
            if metric not in ('read', 'metadata') or row.get('scope') != 'phase':
                return None
            clean.update(metric=metric, scope='phase')
            read_pass = row.get('read_pass')
            if read_pass is not None and (type(read_pass) is not int or not number(read_pass)):
                return None
            clean['read_pass'] = read_pass
            timings = row.get('phase_seconds', {})
            if not isinstance(timings, dict) or any(k not in PHASES or not number(v) for k, v in timings.items()):
                return None
            clean['phase_seconds'] = timings
        if metric == 'metadata' and (clean['bytes_done'] != 0 or any(
                clean[k] is not None for k in ('bytes_total', 'percent', 'speed_bps', 'eta_seconds'))):
            return None
        integers = ('bytes_done', 'bytes_total', 'current_file_index', 'total_files')
        if any(clean[k] is not None and type(clean[k]) is not int for k in integers):
            return None
        if (clean['bytes_done'] is None or clean['updated_at'] is None
                or (clean['bytes_total'] is not None and clean['bytes_done'] > clean['bytes_total'])
                or (clean['percent'] is not None and clean['percent'] > 100)):
            return None
        return clean
    except (OSError, ValueError, TypeError, AttributeError):
        return None


class Progress:
    def __init__(self, root=None, identity=None):
        self.root = root or Path.home() / '.local/share/myct/cloud-jobs'
        self.identity = identity or os.environ.get('MYCT_CLOUD_JOB', '')
        self.enabled = False
        self.sizes = []; self.index = 0; self.base = 0; self.current = 0
        self.rate = None; self.complete = True
        self.row = {'phase': 'preparing', 'bytes_done': 0, 'bytes_total': None,
                    'percent': None, 'speed_bps': None, 'eta_seconds': None}
        try:
            if not re.fullmatch('[0-9a-f]{32}', self.identity):
                return
            info = self.root.stat()
            receipt = self.root / (self.identity + '.json')
            if (self.root.resolve() != self.root or info.st_uid != os.getuid() or info.st_mode & 0o077
                    or receipt.is_symlink()):
                return
            job = json.loads(receipt.read_text())
            # Only the bridge worker and its direct engine child can publish telemetry.
            self.enabled = (job['state'] == 'running' and
                            job.get('worker_pid') in (os.getpid(), os.getppid()))
        except (OSError, ValueError, KeyError, TypeError):
            pass

    def publish(self):
        if not self.enabled:
            return
        name = None
        try:
            self.row['updated_at'] = time.time()
            fd, name = tempfile.mkstemp(prefix='.progress-', dir=self.root)
            with os.fdopen(fd, 'w') as stream:
                json.dump(self.row, stream, allow_nan=False)
                stream.flush(); os.fsync(stream.fileno())
            os.replace(name, self.root / (self.identity + '.progress'))
            fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        except (OSError, ValueError):
            pass  # Telemetry failure must not change archive/restore semantics.
        finally:
            if name:
                try:
                    Path(name).unlink(missing_ok=True)
                except OSError:
                    pass

    def phase(self, phase):
        self.row.update(phase=phase, speed_bps=None, eta_seconds=None)
        self.publish()

    def plan(self, sizes):
        self.sizes = list(sizes); self.index = 0; self.base = 0; self.current = 0
        self.rate = None; self.complete = True
        self.row.update(bytes_done=0, bytes_total=sum(self.sizes), percent=None,
                        total_files=len(self.sizes), current_file_index=0)
        self.publish()

    def begin_copy(self, downloading):
        self.current = 0; self.index += 1
        self.row['current_file_index'] = self.index
        self.phase('downloading' if downloading else 'uploading')

    def stats(self, raw):
        try:
            data = json.loads(raw).get('stats')
            if not isinstance(data, dict) or self.index > len(self.sizes) or not self.index:
                return
            done, total, speed, eta = (data.get(k) for k in ('bytes', 'totalBytes', 'speed', 'eta'))
            expected = self.sizes[self.index - 1]
            if (type(done) is not int or not number(done) or type(total) is not int
                    or not number(total) or total != expected or done > expected or done < self.current):
                return
            self.current = done
            self.row['bytes_done'] = self.base + done
            planned = self.row['bytes_total']
            self.row['percent'] = (100 * (self.base + done) / planned
                                   if self.complete and planned and self.base + done < planned else None)
            valid_rate = number(speed) and speed > 0
            self.rate = (speed if self.rate is None else .3 * speed + .7 * self.rate) if valid_rate else None
            self.row['speed_bps'] = self.rate if valid_rate else 0 if speed == 0 else None
            # A missing Rclone ETA is not evidence for a locally invented estimate.
            self.row['eta_seconds'] = ((planned - self.base - done) / self.rate
                                       if valid_rate and number(eta) and self.complete and planned else None)
            self.publish()
        except (ValueError, TypeError, AttributeError):
            pass

    def end_copy(self):
        self.base += self.current
        if self.index <= len(self.sizes) and self.current != self.sizes[self.index - 1]:
            self.complete = False
            self.row['percent'] = None
        self.phase('verifying')

    def finish(self, success):
        saved = read_progress(self.root, self.identity)
        if saved:
            self.row = saved
        self.row['percent'] = (100 if success and self.row.get('metric') not in ('read', 'metadata') and self.row['bytes_total'] and
                               self.row['bytes_done'] == self.row['bytes_total'] else None)
        self.phase('done' if success else 'failed')


class SpaceProgress:
    """Phase-local bytes returned by read(), never physical SSD or network bytes.

    Metadata preview and execution reads share phase timing. Measurements never enter tokens/catalogs.
    All phase boundaries publish; block callbacks publish at most every 2 seconds.
    """
    def __init__(self, progress, clock=time.monotonic, metric='read'):
        self.progress = progress
        self.clock = clock
        self.started = clock()
        self.phase_name = 'preparing'
        self.timings = {}
        self.samples = []
        self.last_tick = self.started
        self.last_bytes = 0
        self.read_pass = 0
        self.progress.row = {'phase': 'preparing', 'metric': metric, 'scope': 'phase',
                             'bytes_done': 0, 'bytes_total': None, 'percent': None,
                             'speed_bps': None, 'eta_seconds': None, 'phase_seconds': {}}
        self.phase('preparing')

    def phase(self, name):
        now = self.clock()
        self.timings[self.phase_name] = self.timings.get(self.phase_name, 0) + now - self.started
        self.phase_name = name; self.started = now
        self.progress.row.update(phase=name, bytes_done=0, bytes_total=None, percent=None,
                                 speed_bps=None, eta_seconds=None,
                                 phase_seconds=dict(self.timings), read_pass=None)
        self.progress.publish()

    def reading(self, total, library=False):
        if self.progress.row.get('metric') != 'read':
            raise ValueError('Metadata inventory cannot report content reads.')
        self.phase('library_read' if library else 'local_read')
        self.read_pass += 1
        self.progress.row.update(bytes_total=total, read_pass=self.read_pass)
        self.samples = []; self.last_tick = self.clock(); self.last_bytes = 0
        self.progress.publish()

    def end_read(self):
        # Flush the last short interval once per phase, never once per block.
        self.progress.row.update(speed_bps=None, eta_seconds=None)
        self.progress.publish()

    def advance(self, count):
        row = self.progress.row
        row['bytes_done'] += count  # Called only after a successful read, including rereads.
        total = row['bytes_total']
        if total is not None and row['bytes_done'] > total:
            row['bytes_total'] = total = None  # File changed: no trustworthy denominator.
        row['percent'] = 100 * row['bytes_done'] / total if total and row['bytes_done'] < total else None
        now = self.clock()
        if now - self.last_tick < 2:
            return
        rate = (row['bytes_done'] - self.last_bytes) / (now - self.last_tick)
        self.samples = (self.samples + [rate])[-5:]
        row['speed_bps'] = rate
        # >= 3 timed intervals (>= 6 s), positive and reasonably stable observations.
        mean = sum(self.samples) / len(self.samples)
        stable = len(self.samples) >= 3 and min(self.samples) > 0 and max(abs(x - mean) for x in self.samples) <= mean * .5
        row['eta_seconds'] = (total - row['bytes_done']) / mean if stable and total and row['bytes_done'] < total else None
        self.last_tick = now; self.last_bytes = row['bytes_done']
        timings = dict(self.timings)
        timings[self.phase_name] = timings.get(self.phase_name, 0) + now - self.started
        row['phase_seconds'] = timings
        self.progress.publish()
