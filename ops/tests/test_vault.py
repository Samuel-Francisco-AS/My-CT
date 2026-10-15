"""Offline, synthetic safety checks. Never test against the user's real keyring."""
import ast
import json
import os
from pathlib import Path
import subprocess
import unittest

ENTRY = Path(__file__).resolve().parents[1] / "myct-vault"


class VaultOfflineTest(unittest.TestCase):
    def test_syntax_and_existing_only_operations(self):
        code = ENTRY.read_text(encoding="utf-8")
        ast.parse(code)
        for forbidden in ("'CreateCollection'", "'GetSecret'", "'SetSecret'",
                          "'Prompt'", "'Unlock'", "subprocess.run", "getpass.getpass"):
            self.assertNotIn(forbidden, code)
        self.assertIn("UnlockWithMasterPassword", code)
        self.assertIn("authenticated_ssh_session_required", code)

    def test_unlock_rejects_noninteractive_input_before_password_access(self):
        synthetic = "synthetic-password-DO-NOT-USE"
        proc = subprocess.run(
            ["/usr/bin/python3", str(ENTRY), "unlock"],
            input=synthetic, capture_output=True, text=True, timeout=5)
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn(synthetic, proc.stdout + proc.stderr)
        status = json.loads(proc.stdout)
        self.assertEqual(status.get("error_code"), "human_private_ssh_terminal_required")

    def test_status_fails_closed_without_session_bus(self):
        env = dict(os.environ, DBUS_SESSION_BUS_ADDRESS="unix:path=/nonexistent-myct-test-bus")
        proc = subprocess.run(
            ["/usr/bin/python3", str(ENTRY), "status"],
            capture_output=True, text=True, env=env, timeout=5)
        self.assertNotEqual(proc.returncode, 0)
        status = json.loads(proc.stdout)
        self.assertFalse(status["service_available"])
        self.assertFalse(status["login_unlocked"])
        self.assertNotIn("Traceback", proc.stderr)


if __name__ == "__main__":
    unittest.main()
