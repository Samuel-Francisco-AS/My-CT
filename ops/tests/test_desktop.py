"""Restricted helper contract. No real service operation, sudo or installation."""
import pathlib
import subprocess
import tempfile
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[1] / "desktop/myct-desktop-control.cpp"


class DesktopHelperTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="myct-desktop-test-")
        root = pathlib.Path(cls.temporary.name)
        wrapper = root / "wrapper.cpp"
        wrapper.write_text('''
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
static uid_t fake_uid() { return std::getenv("MYCT_TEST_ROOT") ? 0 : 1000; }
static int fake_exec(const char* file, char* const args[], char* const env[]) {
    std::printf("EXEC:%s\\n", file);
    for (int i = 0; args[i]; ++i) std::printf("ARG:%s\\n", args[i]);
    for (int i = 0; env[i]; ++i) std::printf("ENV:%s\\n", env[i]);
    return -1;
}
#define geteuid fake_uid
#define execve fake_exec
#define main helper_entry
#include "''' + str(SOURCE) + '''"
#undef main
int main(int argc, char** argv) { return helper_entry(argc, argv); }
''')
        cls.binary = root / "helper-contract"
        subprocess.run(["g++", "-std=c++20", "-Wall", "-Wextra", "-Werror", str(wrapper), "-o", str(cls.binary)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def run_helper(self, *args, root=False):
        return subprocess.run([str(self.binary), *args], env={"MYCT_TEST_ROOT": "1"} if root else {}, capture_output=True, text=True, check=False)

    def test_rejects_arbitrary_operations_units_and_extra_arguments(self):
        for args in [(), ("restart",), ("start", "sshd.service"), ("stop; reboot",), ("--help",), ("status", "sshd.service")]:
            result = self.run_helper(*args, root=True)
            self.assertEqual(64, result.returncode)
            self.assertNotIn("EXEC:", result.stdout)

    def test_unprivileged_cannot_control_service(self):
        for action in ["start", "stop"]:
            result = self.run_helper(action)
            self.assertEqual(77, result.returncode)
            self.assertNotIn("EXEC:", result.stdout)

    def test_status_is_read_only_and_available_without_root(self):
        for root in [False, True]:
            result = self.run_helper("status", root=root)
            self.assertEqual(71, result.returncode)
            self.assertEqual([
                "EXEC:/usr/bin/systemctl", "ARG:/usr/bin/systemctl", "ARG:--no-pager", "ARG:show",
                "ARG:--value", "ARG:--property=ActiveState", "ARG:vncserver@:1.service",
                "ENV:PATH=/usr/bin", "ENV:LANG=C"
            ], result.stdout.splitlines())

    def test_root_exec_is_absolute_unit_is_fixed_and_environment_is_clean(self):
        for action in ["start", "stop"]:
            result = self.run_helper(action, root=True)
            self.assertEqual(71, result.returncode)  # Mock exec returned; no process was launched.
            self.assertEqual([
                "EXEC:/usr/bin/systemctl", "ARG:/usr/bin/systemctl", "ARG:--no-block",
                "ARG:" + action, "ARG:vncserver@:1.service", "ENV:PATH=/usr/bin", "ENV:LANG=C"
            ], result.stdout.splitlines())


if __name__ == "__main__":
    unittest.main()
