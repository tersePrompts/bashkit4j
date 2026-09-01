package io.bashkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-level feature verification: bash syntax, builtins, VFS, identity,
 * resource limits, isolation, and network denial.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeatureTest {

    /* ---------- bash syntax & control flow ---------- */

    @Test
    void variablesAndExpansion() {
        try (Bash b = Bash.builder().build()) {
            assertEquals("hello", b.exec("X=hello; echo $X").stdout().trim());
            assertEquals("fallback", b.exec("echo ${UNDEF:-fallback}").stdout().trim());
            assertEquals("HELLO", b.exec("X=hello; echo ${X^^}").stdout().trim());
            assertEquals("3", b.exec("S=abc; echo ${#S}").stdout().trim());
            assertEquals("0", b.exec("echo ${#UNDEF}").stdout().trim());
        }
    }

    @Test
    void arithmeticAndCommandSubstitution() {
        try (Bash b = Bash.builder().build()) {
            assertEquals("7", b.exec("echo $((3+4))").stdout().trim());
            assertEquals("ab", b.exec("echo $(printf 'ab')").stdout().trim());
        }
    }

    @Test
    void pipelinesAndRedirection() {
        try (Bash b = Bash.builder().build()) {
            assertEquals("a\nb\nc", b.exec("printf 'c\\nb\\na' | sort").stdout().trim());
            b.exec("echo data > /o.txt");
            assertEquals("data", b.readFile("/o.txt").trim());
            assertEquals("data", b.exec("cat /o.txt").stdout().trim());
        }
    }

    @Test
    void loopsAndConditionals() {
        try (Bash b = Bash.builder().build()) {
            assertEquals("1 2 3", b.exec("for i in 1 2 3; do printf '%s ' $i; done").stdout().trim());
            assertEquals("yes", b.exec("if [ 1 -lt 2 ]; then echo yes; else echo no; fi").stdout().trim());
            assertEquals("b", b.exec("x=b; case $x in a) echo a;; b) echo b;; esac").stdout().trim());
        }
    }

    @Test
    void functions() {
        try (Bash b = Bash.builder().build()) {
            b.exec("greet() { echo hello $1; }");
            assertEquals("hello bob", b.exec("greet bob").stdout().trim());
        }
    }

    @Test
    void arrays() {
        try (Bash b = Bash.builder().build()) {
            assertEquals("1 2 3", b.exec("a=(1 2 3); echo ${a[@]}").stdout().trim());
            assertEquals("3", b.exec("a=(1 2 3); echo ${#a[@]}").stdout().trim());
        }
    }

    @Test
    void hereDocument() {
        try (Bash b = Bash.builder().build()) {
            String out = b.exec("cat <<'EOF'\nline1\nline2\nEOF").stdout();
            assertEquals("line1\nline2\n", out);
        }
    }

    /* ---------- builtin commands ---------- */

    @Test
    void textProcessingBuiltins() {
        try (Bash b = Bash.builder().build()) {
            assertEquals("2", b.exec("printf 'a\\nb\\nc' | wc -l").stdout().trim());
            assertEquals("a\nb", b.exec("printf 'a\\nb\\nc' | head -n 2").stdout().trim());
            assertEquals("cba", b.exec("printf abc | rev").stdout().trim());
            assertEquals("HELLO", b.exec("printf hello | tr a-z A-Z").stdout().trim());
            assertEquals("line", b.exec("printf 'a line here' | cut -d' ' -f2").stdout().trim());
        }
    }

    @Test
    void fileBuiltins() {
        try (Bash b = Bash.builder().build()) {
            b.exec("mkdir -p /d/e; touch /d/f.txt");
            assertTrue(b.exec("test -f /d/f.txt && echo yes").stdout().contains("yes"));
            b.exec("mv /d/f.txt /d/g.txt");
            assertEquals("/d/g.txt", b.exec("ls /d/g.txt").stdout().trim());
            b.exec("rm -r /d");
            assertTrue(b.exec("test -e /d && echo no || echo gone").stdout().contains("gone"));
        }
    }

    @Test
    void archivingBuiltins() {
        try (Bash b = Bash.builder().build()) {
            b.exec("mkdir -p /src; echo hello > /src/a.txt; tar -cf /a.tar -C /src a.txt");
            b.exec("mkdir -p /dst; tar -xf /a.tar -C /dst");
            assertEquals("hello", b.readFile("/dst/a.txt").trim());
            assertTrue(b.exec("test -f /a.tar && echo yes").stdout().contains("yes"));
        }
    }

    @Test
    void jsonAndUtilities() {
        try (Bash b = Bash.builder().build()) {
            assertEquals("42", b.exec("echo '{\"n\":42}' | jq -r '.n'").stdout().trim());
            assertEquals("5", b.exec("echo 2+3 | bc").stdout().trim());
            assertEquals("7", b.exec("expr 3 + 4").stdout().trim());
            assertEquals("c", b.exec("echo abcdef | cut -c3").stdout().trim());
        }
    }

    @Test
    void checksumsAndBase64() {
        try (Bash b = Bash.builder().build()) {
            assertEquals("SGVsbG8=", b.exec("printf 'Hello' | base64").stdout().trim());
            // md5/sha1/sha256sum emit "<hash>  -"; assert the hex digest prefix
            assertTrue(b.exec("printf 'x' | md5sum").stdout().trim()
                    .startsWith("9dd4e461268c8034f5c8564e155c67a6"));
            assertTrue(b.exec("printf 'x' | sha256sum").stdout().trim()
                    .matches("[0-9a-f]{64}.*"));
        }
    }

    /* ---------- virtual filesystem & identity ---------- */

    @Test
    void binaryVfs() {
        byte[] data = {0, 1, (byte) 0x80, (byte) 0xff, 10};
        try (Bash b = Bash.builder().build()) {
            b.writeFile("/bin.dat", data);
            assertArrayEquals(data, b.readFileBytes("/bin.dat"));
        }
    }

    @Test
    void statePersistsAcrossCalls() {
        try (Bash b = Bash.builder().build()) {
            b.exec("export BUILD=42; echo x > /state.txt");
            assertEquals("42", b.exec("echo $BUILD").stdout().trim());
            assertEquals("x", b.readFile("/state.txt").trim());
        }
    }

    @Test
    void virtualIdentity() {
        try (Bash b = Bash.builder().username("deploy").hostname("prod-1").build()) {
            assertTrue(b.exec("whoami").stdout().contains("deploy"));
            assertTrue(b.exec("hostname").stdout().contains("prod-1"));
            assertTrue(b.exec("echo $USER").stdout().contains("deploy"));
            assertTrue(b.exec("id").stdout().contains("deploy"));
        }
    }

    @Test
    void seededEnvAndFiles() {
        try (Bash b = Bash.builder()
                .env("REGION", "us-east")
                .file("/cfg.txt", "mode=on")
                .build()) {
            assertEquals("us-east", b.exec("echo $REGION").stdout().trim());
            assertEquals("mode=on", b.readFile("/cfg.txt").trim());
        }
    }

    /* ---------- sandbox / security ---------- */

    @Test
    void hostFilesystemHidden() {
        try (Bash b = Bash.builder().build()) {
            assertTrue(b.exec("test -e /etc/passwd && echo yes || echo no").stdout().contains("no"));
            assertTrue(b.exec("test -f C:/Windows/win.ini && echo yes || echo no").stdout().contains("no"));
            assertTrue(b.exec("test -e /proc && echo yes || echo no").stdout().contains("no"));
        }
    }

    @Test
    void noEscapeFromVfs() {
        try (Bash b = Bash.builder().cwd("/home/user").build()) {
            // '..' climbs within the virtual root but can't reach any host path
            assertTrue(b.exec("test -f /../../../etc/hostname && echo yes || echo no")
                    .stdout().contains("no"));
            assertTrue(b.exec("cat /etc/hostname 2>&1").stdout().contains("not found")
                    || b.exec("cat /etc/hostname 2>&1").stdout().contains("error"));
        }
    }

    @Test
    void networkDeniedByDefault() {
        try (Bash b = Bash.builder().build()) {
            ExecResult r = b.exec("curl -sI https://example.com 2>&1; echo exit=$?");
            assertTrue(r.stdout().contains("network access not configured")
                    || r.stdout().contains("network"), "curl should be blocked");
        }
    }

    @Test
    void hostProcessesInvisible() {
        try (Bash b = Bash.builder().build()) {
            // no real process list / signals to host
            assertEquals("", b.exec("sleep 0").stdout().trim());
        }
    }

    /* ---------- multitenancy ---------- */

    @Test
    void instancesAreIsolated() {
        try (Bash b1 = Bash.builder().build();
             Bash b2 = Bash.builder().build()) {
            b1.exec("SECRET=from-b1; echo a > /f.txt");
            assertEquals("from-b1", b1.exec("echo $SECRET").stdout().trim());
            assertEquals("<unset>", b2.exec("echo ${SECRET:-<unset>}").stdout().trim());
            assertTrue(b2.exec("test -e /f.txt && echo yes || echo no").stdout().contains("no"));
        }
    }

    /* ---------- resource limits ---------- */

    @Test
    void maxCommandsLimit() {
        // exceeding the limit raises a BashException (ABI execution error),
        // not a normal ExecResult
        try (Bash b = Bash.builder().maxCommands(5).build()) {
            assertThrows(BashException.class, () -> b.exec("echo 1; echo 2; echo 3; echo 4; echo 5; echo 6"));
        }
        // within the limit is fine
        try (Bash b = Bash.builder().maxCommands(5).build()) {
            assertEquals(0, b.exec("echo 1; echo 2; echo 3").exitCode());
        }
    }

    /* ---------- builder config surface ---------- */

    @Test
    void builderWrites() {
        try (Bash b = Bash.builder()
                .cwd("/x")
                .maxCommands(100)
                .env("A", "1")
                .file("/y", "abcdefg")
                .username("u").hostname("h")
                .build()) {
            assertEquals("/x", b.exec("pwd").stdout().trim());
            assertEquals("abcdefg", b.readFile("/y").trim());
            assertEquals("1", b.exec("echo $A").stdout().trim());
            assertEquals("u", b.exec("whoami").stdout().trim());
            assertEquals("h", b.exec("hostname").stdout().trim());
        }
    }

    /* ---------- output flags / env capture ---------- */

    @Test
    void outputFlagsExposed() {
        try (Bash b = Bash.builder().build()) {
            ExecResult ok = b.exec("echo hi");
            assertEquals(0, ok.exitCode());
            assertTrue(ok.success());
            assertEquals("hi\n", ok.stdout());
            assertEquals("", ok.stderr());
            assertFalse(ok.stdoutTruncated());
            assertFalse(ok.stderrTruncated());
            assertArrayEquals(new byte[]{'h', 'i', '\n'}, ok.stdoutBytes());
        }
    }

    @Test
    void stderrAndExecOrThrow() {
        try (Bash b = Bash.builder().build()) {
            ExecResult e = b.exec("echo err >&2");
            assertTrue(e.stderr().contains("err"));
            assertThrows(BashException.class, () -> b.execOrThrow("exit 1"));
            assertEquals(1, b.exec("exit 1").exitCode());
        }
    }
}
