"""Tests for `release.sh publish` with stub gh, apksigner, aapt2: python3 tools/hooks/test_release_publish.py"""
import hashlib
import os
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, ".claude/skills/unbraid-release/scripts/release.sh")
CERT = "42ba8e3d521cde47b7c3e69394ed9773910db3c0cddcba289cea5db43909f5eb"
V, CODE = "9.8.7", "321"


def exe(path, body):
    with open(path, "w") as f:
        f.write("#!/bin/bash\n" + body)
    os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)


class Publish(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        t = self.tmp.name
        self.repo = os.path.join(t, "repo")
        scripts = os.path.join(self.repo, ".claude/skills/unbraid-release/scripts")
        cl = os.path.join(self.repo, ".claude/skills/unbraid-changelog/scripts")
        os.makedirs(scripts)
        os.makedirs(cl)
        shutil.copy(SCRIPT, scripts)
        self.script = os.path.join(scripts, "release.sh")
        open(os.path.join(cl, "changelog.py"), "w").write("")  # notes-check always passes
        os.makedirs(os.path.join(self.repo, "app"))
        self.gradle = os.path.join(self.repo, "app/build.gradle.kts")
        self.write_gradle(V, CODE)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")
        self.git("config", "commit.gpgsign", "false")
        self.git("add", "-A")
        self.git("commit", "-qm", "v")
        self.git("tag", "v" + V)
        # Stubs: apksigner prints CERT_ENV, aapt2 prints the badging line, gh logs its calls.
        self.bin = os.path.join(t, "bin")
        self.bt = os.path.join(t, "bt")
        os.makedirs(self.bin)
        os.makedirs(self.bt)
        self.ghlog = os.path.join(t, "gh.log")
        exe(os.path.join(self.bin, "gh"), 'echo "$@" >> "%s"\n' % self.ghlog)
        exe(os.path.join(self.bt, "apksigner"), 'echo "Signer #1 certificate SHA-256 digest: ${STUB_CERT}"\n')
        exe(os.path.join(self.bt, "aapt2"),
            'echo "package: name=\'x\' versionCode=\'${STUB_CODE}\' versionName=\'${STUB_NAME}\' platformBuildVersionName=\'1\'"\n')
        self.d = os.path.join(self.repo, "dist", V)
        os.makedirs(self.d)
        self.apk = os.path.join(self.d, "unbraid-%s.apk" % V)
        self.stage(b"real apk")
        open(os.path.join(self.d, "notes.md"), "w").write("notes\n")
        self.env = dict(os.environ, PATH=self.bin + ":" + os.environ["PATH"], BUILD_TOOLS=self.bt,
                        STUB_CERT=CERT, STUB_CODE=CODE, STUB_NAME=V)

    def tearDown(self):
        self.tmp.cleanup()

    def write_gradle(self, name, code):
        open(self.gradle, "w").write('android {\n  defaultConfig {\n    versionCode = %s\n    versionName = "%s"\n  }\n}\n' % (code, name))

    def git(self, *a):
        return subprocess.run(["git", *a], cwd=self.repo, check=True, capture_output=True).stdout.decode().strip()

    def stage(self, data):
        open(self.apk, "wb").write(data)
        h = hashlib.sha256(data).hexdigest()
        open(os.path.join(self.d, "SHA256SUMS"), "w").write("%s  unbraid-%s.apk\n" % (h, V))

    def publish(self, **env):
        r = subprocess.run(["bash", self.script, "publish", V], cwd=self.repo, env=dict(self.env, **env), capture_output=True, text=True)
        return r, os.path.exists(self.ghlog) and "release create" in open(self.ghlog).read()

    def refuses(self, text, **env):
        r, created = self.publish(**env)
        self.assertNotEqual(r.returncode, 0, r.stdout)
        self.assertIn(text, r.stderr)
        self.assertFalse(created, "gh release create must not run")

    def test_good_release_publishes(self):
        r, created = self.publish()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(created)

    def test_substituted_apk(self):
        open(self.apk, "wb").write(b"swapped")
        self.refuses("SHA256SUMS does not match")

    def test_stale_checksum(self):
        open(os.path.join(self.d, "SHA256SUMS"), "w").write("%s  unbraid-%s.apk\n" % ("0" * 64, V))
        self.refuses("SHA256SUMS does not match")

    def test_wrong_signer(self):
        self.refuses("not signed with the release key", STUB_CERT="f" * 64)

    def test_wrong_version_name(self):
        self.refuses("versionName is not", STUB_NAME="9.8.6")

    def test_wrong_version_code(self):
        self.refuses("versionCode", STUB_CODE="320")

    def test_missing_tag(self):
        self.git("tag", "-d", "v" + V)
        self.refuses("does not exist")

    def test_tag_on_another_commit(self):
        open(os.path.join(self.repo, "x"), "w").write("x")
        self.git("add", "-A")
        self.git("commit", "-qm", "later")
        self.refuses("does not point at the current commit")

    def test_tag_with_other_version(self):
        self.git("tag", "-d", "v" + V)
        self.write_gradle("9.8.6", CODE)
        self.git("commit", "-qam", "other")
        self.git("tag", "v" + V)
        self.refuses("does not have versionName")


if __name__ == "__main__":
    unittest.main()
