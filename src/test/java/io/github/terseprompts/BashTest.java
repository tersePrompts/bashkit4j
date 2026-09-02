package io.github.terseprompts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashTest {

    @Test
    void echoHelloWorld() {
        BashkitRuntime.library();
        try (Bash bash = Bash.builder().build()) {
            ExecResult r = bash.exec("echo hello world");
            assertEquals("hello world\n", r.stdout());
            assertEquals(0, r.exitCode());
            assertTrue(r.success());
        }
    }

    @Test
    void pipeAndVfsRoundTrip() {
        try (Bash bash = Bash.builder().build()) {
            bash.writeFile("/in.txt", "banana\napple\ncherry\n");
            ExecResult r = bash.exec("sort /in.txt | tr a-z A-Z");
            assertEquals("APPLE\nBANANA\nCHERRY\n", r.stdout());
            assertEquals(0, r.exitCode());
        }
    }

    @Test
    void exitCodeIsNormalResult() {
        try (Bash bash = Bash.builder().build()) {
            ExecResult r = bash.exec("exit 7");
            assertEquals(7, r.exitCode());
            assertTrue(!r.success());
        }
    }

    @Test
    void statePersistsAcrossCalls() {
        try (Bash bash = Bash.builder().build()) {
            bash.exec("X=42");
            assertEquals("42\n", bash.exec("echo $X").stdout());
        }
    }

    @Test
    void versionAndAbi() {
        assertEquals(1, BashkitRuntime.abiVersion());
        assertTrue(BashkitRuntime.version().length() > 0);
    }
}
