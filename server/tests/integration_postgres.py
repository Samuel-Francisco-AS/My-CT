"""Explicit real-database test: make test-db. Never creates/starts a cluster."""
import os
import pathlib
import subprocess
import unittest
import uuid

BINARY = pathlib.Path(__file__).resolve().parents[1] / "myct-core"


class PostgresIntegrationTest(unittest.TestCase):
    def test_record_survives_core_exit(self):
        self.assertTrue(os.environ.get("MYCT_PGSOCKET", "").startswith("/"),
                        "Set MYCT_PGSOCKET to the dedicated running cluster")
        # These inherited libpq options must not redirect the local CLI.
        env = dict(os.environ, PGHOSTADDR="192.0.2.1", PGSERVICE="myct_nonexistent_service")
        event = "integration." + uuid.uuid4().hex
        # check_output waits for the writer to exit before a new Core reads it.
        saved = subprocess.check_output(
            [str(BINARY), "--record-event", event], text=True, timeout=10, env=env).strip()
        event_id, timestamp, saved_type = saved.split("\t")
        self.assertGreater(int(event_id), 0)
        self.assertTrue(timestamp.endswith("Z"))
        self.assertEqual(saved_type, event)
        loaded = subprocess.check_output(
            [str(BINARY), "--get-event", event_id], text=True, timeout=10, env=env).strip()
        self.assertEqual(loaded, saved)
        print(f"Real PostgreSQL, separate Core processes: {loaded}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
