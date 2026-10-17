#!/usr/bin/env python3
"""Real compilation + interpreted WASI. Host tests are NOT Moto evidence."""
import os
from pathlib import Path
import selectors
import subprocess
import sys
import tempfile
import time
import unittest

WORKER = str(Path(sys.argv.pop(1)).resolve())


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='myct-l1c-')
        self.directory = Path(self.temp.name)
        self.source = self.directory / 'programa.c'
        self.wasm = self.directory / 'programa.wasm'

    def tearDown(self):
        self.temp.cleanup()

    def compile(self, code, valid=True):
        self.source.write_text(code)
        result = subprocess.run([WORKER, 'compile', str(self.source), str(self.wasm)], capture_output=True, timeout=10)
        self.assertEqual(result.returncode == 0, valid, result.stderr.decode())
        if valid:
            self.assertEqual(self.wasm.read_bytes()[:8], b'\0asm\1\0\0\0')
        return result

    def run_wasm(self, stdin=b''):
        return subprocess.run([WORKER, 'run', str(self.wasm)], input=stdin, capture_output=True, timeout=10)

    def test_valid_console_arithmetic_branches_loops_function_and_exit(self):
        self.compile('''#include <stdio.h>
int dobro(int n) { return n*2; }
int main(void) { int n=6, sum=0; for (int i=0;i<3;++i) sum+=i;
if (sum==3) printf("valor=%d\\n",dobro(n)); else puts("erro");
puts("My-CT compilando offline!"); return 7; }''')
        r = self.run_wasm()
        self.assertEqual(r.returncode, 7)
        self.assertEqual(r.stdout, b'valor=12\nMy-CT compilando offline!\n')
        self.assertIn(b'encerrado', r.stderr)

    def test_real_syntax_diagnostic(self):
        r = self.compile('#include <stdio.h>\nint main(void) { puts("erro") return 0; }', False)
        self.assertIn(b'programa.c', r.stderr)
        self.assertIn(b'expecting ;', r.stderr.lower())

    def test_empty_and_invalid_file(self):
        self.compile('', False)
        self.compile('isto nao e C', False)

    def test_scanf_prompt_before_keyboard_input(self):
        self.compile('''#include <stdio.h>
int main(void) { int n; printf("Numero: "); if (scanf("%d", &n)!=1) return 2;
printf("dobro=%d\\n",n*2); return 0; }''')
        p = subprocess.Popen([WORKER, 'run', str(self.wasm)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            with selectors.DefaultSelector() as sel:
                sel.register(p.stdout, selectors.EVENT_READ)
                self.assertTrue(sel.select(3), 'Prompt did not arrive before input')
                prompt = os.read(p.stdout.fileno(), 8)
            self.assertEqual(prompt, b'Numero: ')
            out, err = p.communicate(b'21\n', timeout=3)
            self.assertEqual(p.returncode, 0, err)
            self.assertEqual(out, b'dobro=42\n')
        finally:
            p.kill(); p.communicate()

    def test_recompile_changed_source(self):
        self.compile('int main(void) { return 11; }')
        original = self.wasm.read_bytes()
        self.assertEqual(self.run_wasm().returncode, 11)
        self.compile('int main(void) { return 22; }')
        self.assertNotEqual(original, self.wasm.read_bytes())
        self.assertEqual(self.run_wasm().returncode, 22)

    def test_cancel_infinite_loop_and_input_wait(self):
        for code in ['int main(void) { while(1) {} return 0; }', '#include <stdio.h>\nint main(void) { int x; scanf("%d",&x); return 0; }']:
            self.compile(code)
            p = subprocess.Popen([WORKER, 'run', str(self.wasm)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                time.sleep(.05)
                self.assertIsNone(p.poll())
                start = time.monotonic(); p.kill(); p.wait(timeout=2)
                self.assertLess(time.monotonic()-start, 2)
                self.assertLess(p.returncode, 0)
            finally:
                p.kill(); p.communicate()

    def test_memory_limit_and_no_filesystem_preopen(self):
        self.compile('''#include <stdlib.h>
#include <stdio.h>
int main(void) { void *p=malloc(32*1024*1024); if (p != NULL) return 1;
FILE *fp=fopen("/etc/passwd","r"); if (fp != NULL) return 2; return 0; }''')
        r = self.run_wasm()
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_runtime_trap_does_not_modify_source(self):
        self.compile('int main(void) { int *p=(int*)0; return p[9999999]; }')
        before = self.source.read_bytes()
        r = self.run_wasm()
        self.assertEqual(r.returncode, 125)
        self.assertIn(b'falha', r.stderr)
        self.assertEqual(self.source.read_bytes(), before)

    def test_missing_source_and_invalid_artifact(self):
        r = subprocess.run([WORKER, 'compile', str(self.source), str(self.wasm)], capture_output=True)
        self.assertNotEqual(r.returncode, 0)
        self.wasm.write_bytes(b'invalid!')
        self.assertEqual(self.run_wasm().returncode, 125)


unittest.main()
