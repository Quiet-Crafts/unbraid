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
        # git: pretend the checkout is already at the pin.
        exe(os.path.join(self.bin, "git"),
            '[ "$1" = rev-parse ] && echo c005f35300ce76427dc285481ec5695f5b179cd2\nexit 0\n')
        # go: `install` writes helpers into $GOBIN, `version -m` reports STUB_VERSION for them.
        exe(os.path.join(self.bin, "go"),
            'case "$1" in\n'
            ' install) for h in gomobile gobind; do\n'
            '   printf \'#!/bin/bash\\n[ "$1" = bind ] && { echo "gomobile=$0 gobind=$(command -v gobind)" > "%s"; exit 1; }\\n\' > "$GOBIN/$h"\n'
            '   chmod +x "$GOBIN/$h"; done ;;\n'
            ' version) printf "\\tmod\\tgithub.com/sagernet/gomobile\\t%%s\\th1:x\\n" "${STUB_VERSION:-v0.1.13}" ;;\n'
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


if __name__ == "__main__":
    unittest.main()
