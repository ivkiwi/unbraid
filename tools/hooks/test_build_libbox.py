"""Tests for the helper selection in build-libbox.sh, with stub go, git and helpers (no real build):
python3 tools/hooks/test_build_libbox.py"""
import os
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, "tools/build-libbox.sh")


def exe(path, body):
    with open(path, "w") as f:
        f.write("#!/bin/bash\n" + body)
    os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)


class Helpers(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        t = self.tmp.name
        os.makedirs(os.path.join(t, "repo/tools"))
        shutil.copy(SCRIPT, os.path.join(t, "repo/tools"))
        self.script = os.path.join(t, "repo/tools/build-libbox.sh")
        self.work = os.path.join(t, "work")
        os.makedirs(os.path.join(self.work, "sing-box"))
        self.log = os.path.join(t, "bind.log")
        self.bin = os.path.join(t, "bin")  # first on PATH, holds go and git stubs and stale helpers
        os.makedirs(self.bin)
        # git: pretend the sing-box and netbird checkouts are already at their pins.
        exe(os.path.join(self.bin, "git"),
            'case "$*" in\n'
            ' *netbird*rev-parse*) echo 499a86f4bc4795310ead5f06ecd5907e585acef4 ;;\n'
            ' rev-parse*) echo c005f35300ce76427dc285481ec5695f5b179cd2 ;;\n'
            'esac\nexit 0\n')
        # go: `install` writes helpers into $GOBIN, `version -m` reports STUB_VERSION for them.
        exe(os.path.join(self.bin, "go"),
            'case "$1" in\n'
            ' install) for h in gomobile gobind; do\n'
            '   printf \'#!/bin/bash\\n[ "$1" = bind ] && { echo "gomobile=$0 gobind=$(command -v gobind)" > "%s"; exit 1; }\\n\' > "$GOBIN/$h"\n'
            '   chmod +x "$GOBIN/$h"; done ;;\n'
            ' version) printf "\\tmod\\tgithub.com/sagernet/gomobile\\t%%s\\th1:x\\n" "${STUB_VERSION:-v0.1.13}" ;;\n'
            ' test) [ -n "$STUB_TEST_OUT" ] && cat "$STUB_TEST_OUT"; exit "${STUB_TEST_EXIT:-0}" ;;\n'
            'esac\n' % self.log)
        self.stale = os.path.join(t, "stale")  # a custom GOBIN and a GOPATH/bin with old helpers
        os.makedirs(self.stale)
        for h in ("gomobile", "gobind"):
            exe(os.path.join(self.stale, h), 'echo "stale" > "%s"; exit 1\n' % self.log)
            shutil.copy(os.path.join(self.stale, h), os.path.join(self.bin, h))

    def tearDown(self):
        self.tmp.cleanup()

    def run_script(self, **env):
        e = dict(os.environ, PATH=self.bin + ":" + os.environ["PATH"], WORK=self.work, GOPATH=self.stale, **env)
        return subprocess.run(["bash", self.script], env=e, capture_output=True, text=True)

    def test_private_helpers_are_used(self):
        r = self.run_script(GOBIN=self.stale)
        self.assertNotEqual(r.returncode, 0)  # the stub bind fails on purpose
        used = open(self.log).read().strip()
        priv = os.path.join(self.work, "bin")
        self.assertEqual(used, "gomobile=%s/gomobile gobind=%s/gobind" % (priv, priv))

    def test_wrong_helper_version_stops_before_bind(self):
        r = self.run_script(STUB_VERSION="v0.1.12")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("is not v0.1.13", r.stdout)
        self.assertFalse(os.path.exists(self.log), "bind must not run")

    # The engine's init check before the bind: what NetBird's and sing-box's daemon.proto clash on goes on; anything else stops.
    KNOWN = "".join(
        'WARNING: proto: file "daemon/started_service.proto" has a name conflict over daemon.%s\n'
        '\tpreviously from: "github.com/netbirdio/netbird/client/proto"\n'
        '\tcurrently from:  "github.com/sagernet/sing-box/daemon"\n'
        'See https://protobuf.dev/reference/go/faq#namespace-conflict\n\n' % n
        for n in ("LogLevel", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "PANIC")) + (
        'WARNING: proto: enum daemon.LogLevel is already registered\n'
        '\tpreviously from: "github.com/netbirdio/netbird/client/proto"\n'
        '\tcurrently from:  "github.com/sagernet/sing-box/daemon"\n'
        'ok  \tgithub.com/sagernet/sing-box/experimental/libbox\t0.6s [no tests to run]\n')

    def init_check(self, out, code=0):
        p = os.path.join(self.tmp.name, "test.out")
        with open(p, "w") as f:
            f.write(out)
        return self.run_script(STUB_TEST_OUT=p, STUB_TEST_EXIT=str(code))

    def test_known_clash_goes_on_to_bind(self):
        r = self.init_check(self.KNOWN)
        self.assertNotIn("protobuf name clash", r.stdout)
        self.assertTrue(os.path.exists(self.log), "bind must run")

    def test_any_other_clash_stops_before_bind(self):
        for extra in ('WARNING: proto: file "daemon/started_service.proto" has a name conflict over daemon.Version\n',
                      'WARNING: proto: message daemon.Version is already registered\n',
                      'WARNING: proto: file "other.proto" has a name conflict over daemon.LogLevel\n',
                      '\tpreviously from: "example.com/other/proto"\n'):
            r = self.init_check(self.KNOWN + extra)
            self.assertIn("a new protobuf name clash", r.stdout, extra)
            self.assertFalse(os.path.exists(self.log), "bind must not run")

    def test_a_panic_at_init_stops_before_bind(self):
        r = self.init_check('panic: proto: file "daemon/started_service.proto" has a name conflict\nFAIL\n', 1)
        self.assertIn("engine init failed", r.stdout)
        self.assertFalse(os.path.exists(self.log), "bind must not run")


if __name__ == "__main__":
    unittest.main()
