"""Offline, synthetic safety checks. Never test against the user's real keyring."""
import ast
import json
import importlib.machinery
import importlib.util
import stat
from unittest.mock import patch
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
            ["/usr/bin/python3", "-I", str(ENTRY), "unlock"],
            input=synthetic, capture_output=True, text=True, timeout=5)
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn(synthetic, proc.stdout + proc.stderr)
        status = json.loads(proc.stdout)
        self.assertEqual(status.get("error_code"), "human_private_ssh_terminal_required")

    def test_status_fails_closed_without_session_bus(self):
        env = dict(os.environ, DBUS_SESSION_BUS_ADDRESS="unix:path=/nonexistent-myct-test-bus")
        proc = subprocess.run(
            ["/usr/bin/python3", "-I", str(ENTRY), "status"],
            capture_output=True, text=True, env=env, timeout=5)
        self.assertNotEqual(proc.returncode, 0)
        status = json.loads(proc.stdout)
        self.assertFalse(status["service_available"])
        self.assertFalse(status["login_unlocked"])
        self.assertNotIn("Traceback", proc.stderr)


class SshLogindBindingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        loader = importlib.machinery.SourceFileLoader("myct_vault_synthetic", str(ENTRY))
        spec = importlib.util.spec_from_loader(loader.name, loader)
        cls.helper = importlib.util.module_from_spec(spec)
        loader.exec_module(cls.helper)

    def properties(self, tty="pts/7"):
        return {"Remote": True, "Service": "sshd", "Type": "tty",
                "Class": "user", "User": (os.getuid(), "/user"), "TTY": tty}

    def test_exact_login_tty_accepted_without_secondary_lookup(self):
        with patch.object(self.helper.os, "stat", side_effect=AssertionError("must not inspect other fds")):
            self.helper.require_authenticated_ssh_logind(
                self.properties("pts/42"), "/dev/pts/42", Path("/fake/shell"))

    def test_secondary_private_tty_accepted_only_when_shell_owns_same_device(self):
        from types import SimpleNamespace
        st = lambda dev: SimpleNamespace(st_mode=stat.S_IFCHR | 0o600, st_rdev=dev)
        with patch.object(self.helper.os, "stat", return_value=st(100)), patch.object(self.helper.os, "fstat", return_value=st(100)):
            self.helper.require_authenticated_ssh_logind(
                self.properties("pts/1"), "/dev/pts/42", Path("/fake/shell"))
        with patch.object(self.helper.os, "stat", return_value=st(100)), patch.object(self.helper.os, "fstat", return_value=st(200)):
            with self.assertRaises(self.helper.Blocked) as blocked:
                self.helper.require_authenticated_ssh_logind(
                    self.properties("pts/1"), "/dev/pts/42", Path("/fake/shell"))
            self.assertEqual(str(blocked.exception), "ssh_secondary_tty_unverified")

    def test_secondary_tty_never_bypasses_ssh_logind_identity(self):
        for field, value in (("Remote", False), ("Service", "agent"),
                             ("Type", "unspecified"), ("Class", "background"),
                             ("User", (os.getuid() + 1, "/another"))):
            invalid = self.properties("pts/1")
            invalid[field] = value
            with self.assertRaises(self.helper.Blocked):
                self.helper.require_authenticated_ssh_logind(
                    invalid, "/dev/pts/42", Path("/fake/shell"))



if __name__ == "__main__":
    unittest.main()
