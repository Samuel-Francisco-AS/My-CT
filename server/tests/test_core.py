import http.client
import json
import pathlib
import socket
import subprocess
import time
import unittest

BINARY = pathlib.Path(__file__).resolve().parents[1] / "myct-core"


class CoreSmokeTest(unittest.TestCase):
    def test_cli_returns_real_numeric_metrics(self):
        response = subprocess.check_output([str(BINARY), "--once"], text=True)
        sample = json.loads(response)
        self.assertEqual(sample["status"], "ok")
        self.assertGreaterEqual(sample["cpu_percent"], 0)
        self.assertLessEqual(sample["cpu_percent"], 100)
        self.assertGreater(sample["ram_total_bytes"], 0)
        self.assertLessEqual(sample["ram_available_bytes"], sample["ram_total_bytes"])
        self.assertGreater(sample["disk_total_bytes"], 0)
        self.assertLessEqual(sample["disk_available_bytes"], sample["disk_total_bytes"])

    def test_http_is_read_only_and_loopback(self):
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        process = subprocess.Popen([str(BINARY), "--port", str(port)], stdout=subprocess.DEVNULL)
        try:
            for _ in range(50):
                if process.poll() is not None:
                    self.fail("Core stopped early")
                try:
                    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
                    conn.request("GET", "/v1/health")
                    result = conn.getresponse()
                    data = json.loads(result.read())
                    self.assertEqual(result.status, 200)
                    self.assertEqual(data["status"], "ok")
                    conn.close()
                    break
                except ConnectionRefusedError:
                    time.sleep(0.02)
            else:
                self.fail("Core did not start")

            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
            conn.request("POST", "/v1/health")
            self.assertEqual(conn.getresponse().status, 405)
            conn.close()
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
            conn.request("GET", "/admin")
            self.assertEqual(conn.getresponse().status, 404)
            conn.close()
        finally:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)


if __name__ == "__main__":
    unittest.main()
