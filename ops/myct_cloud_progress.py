"""Opt-in numeric telemetry, separate from authoritative catalogs and job receipts."""
import json
import math
import os
from pathlib import Path
import re
import stat
import tempfile
import time

PHASES = {'preparing', 'hashing', 'uploading', 'downloading', 'verifying', 'done', 'failed'}


def number(value):
    return type(value) in (int, float) and 0 <= value <= 2**53 - 1 and math.isfinite(value)


def read_progress(root, identity):
    try:
        if not re.fullmatch('[0-9a-f]{32}', identity):
            return None
        path = root / (identity + '.progress')
        info = path.lstat()
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077 or info.st_nlink != 1 or info.st_size > 4096):
            return None
        row = json.loads(path.read_text())
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
        self.row['percent'] = (100 if success and self.row['bytes_total'] and
                               self.row['bytes_done'] == self.row['bytes_total'] else None)
        self.phase('done' if success else 'failed')
