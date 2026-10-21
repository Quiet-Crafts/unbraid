"""Tests for the pre-push guard: python3 tools/hooks/test_pre_push.py"""
import os
import subprocess
import tempfile
import unittest

HOOK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "pre-push")
ZERO = "0" * 40


class PrePush(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = self.tmp.name
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")
        self.git("config", "commit.gpgsign", "false")
        self.commit({"README.md": "x"})

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True).stdout.decode().strip()

    def commit(self, files, remove=()):
        for name, text in files.items():
            path = os.path.join(self.repo, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(text)
            self.git("add", "-f", "--", name)
        for name in remove:
            self.git("rm", "-q", "--", name)
        self.git("commit", "-q", "--allow-empty", "-m", "c")
        return self.git("rev-parse", "HEAD")

    def push(self, sha=None, hook=HOOK, ref="refs/heads/main"):
        sha = sha or self.git("rev-parse", "HEAD")
        return subprocess.run([hook, "origin", "url"], cwd=self.repo, input=f"{ref} {sha} {ref} {ZERO}\n".encode(),
                              capture_output=True).returncode

    def test_clean_history_passes(self):
        self.commit({"app/src/main/A.kt": "a", "tools/hooks/x.py": "b"})
        self.assertEqual(self.push(), 0)

    def test_deletion_passes(self):
        self.assertEqual(self.push(sha=ZERO), 0)

    def test_forbidden_paths_fail(self):
        for name in ["_INTERNAL/x.md", "docs/plan.md", "a/b/.secrets/k", ".claude/s.json", ".playwright-mcp/x",
                     "CHANGELOG.md", "sub/AGENTS.md", "release.jks", "app/x.pem", "deep/dir/wg0.conf",
                     "app/src/test/resources/other/x.ovpn", "app/src/test/resources/ovpn/x.key", "odd\nname.key",
                     "spaced name.p12", "a.pfx", "x.keystore", "app.apk", "app.aab", "lib.aar", "b.bundle",
                     "local.properties", "x/.DS_Store", "probe/a.kt", "dist/a"]:
            with self.subTest(name=name):
                self.setUp()
                self.commit({name: "secret"})
                self.assertEqual(self.push(), 1)

    def test_added_then_deleted_fails(self):
        self.commit({"docs/plan.md": "x"})
        self.commit({}, remove=["docs/plan.md"])
        self.assertEqual(self.push(), 1)

    def test_reviewed_fixture_passes(self):
        self.commit({"app/src/test/resources/ovpn/nord.ovpn": "client"})
        self.assertEqual(self.push(), 0)

    def test_annotated_tag_is_checked(self):
        self.commit({"docs/x.md": "x"})
        self.git("tag", "-a", "-m", "t", "v1")
        self.assertEqual(self.push(sha=self.git("rev-parse", "v1"), ref="refs/tags/v1"), 1)

    def test_tag_of_a_blob_fails(self):
        blob = self.git("rev-parse", "HEAD:README.md")
        self.git("tag", "-a", "-m", "t", "b1", blob)
        self.assertEqual(self.push(sha=self.git("rev-parse", "b1"), ref="refs/tags/b1"), 1)

    def test_tag_of_a_tree_fails(self):
        self.git("tag", "-a", "-m", "t", "t1", self.git("rev-parse", "HEAD^{tree}"))
        self.assertEqual(self.push(sha=self.git("rev-parse", "t1"), ref="refs/tags/t1"), 1)

    def test_replaced_commit_is_still_checked(self):
        bad = self.commit({"docs/x.md": "x"})
        self.git("checkout", "-q", "HEAD^")
        clean = self.commit({"app/A.kt": "a"})
        self.git("replace", bad, clean)
        self.assertEqual(self.push(sha=bad), 1)

    def test_shallow_clone_fails(self):
        self.commit({"app/A.kt": "a"})
        clone = self.repo + "-shallow"
        subprocess.run(["git", "clone", "-q", "--depth", "1", "file://" + self.repo, clone], check=True, capture_output=True)
        try:
            self.repo, orig = clone, self.repo
            self.assertEqual(self.push(), 1)
        finally:
            self.repo = orig
            subprocess.run(["rm", "-rf", clone], check=True)

    def test_missing_object_fails(self):
        self.assertEqual(self.push(sha="1" * 40), 1)

    def test_legacy_ancestry_fails(self):
        root = self.git("rev-list", "--max-parents=0", "HEAD")
        self.commit({"app/A.kt": "a"})
        hook = os.path.join(self.repo, "..", os.path.basename(self.repo) + "-hook")
        with open(HOOK) as f, open(hook, "w") as g:
            g.write(f.read().replace('OLD_ROOT = "52c2d10a10df2711dd7b3ac158c2ae743d29d6cf"', f'OLD_ROOT = "{root}"'))
        os.chmod(hook, 0o755)
        try:
            self.assertEqual(self.push(hook=hook), 1)
        finally:
            os.remove(hook)


if __name__ == "__main__":
    unittest.main()
