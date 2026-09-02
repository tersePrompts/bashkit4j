package io.github.terseprompts;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Vanilla type tests: strings, ints, booleans, bytes, maps, arrays, exceptions. */
class VanillaBashTest {

    @Test
    void stringTypes() {
        try (Bash bash = Bash.builder().build()) {
            assertEquals("hi\n", bash.exec("echo hi").stdout());
            assertTrue(bash.exec("echo hi").stderr().isEmpty());
            // empty output
            assertEquals("", bash.exec("true").stdout());
        }
    }

    @Test
    void intTypes() {
        try (Bash bash = Bash.builder().build()) {
            assertEquals(0, bash.exec("echo ok").exitCode());
            assertEquals(3, bash.exec("exit 3").exitCode());
            assertEquals(42, Integer.parseInt(bash.exec("echo $((6*7))").stdout().trim()));
        }
    }

    @Test
    void booleanTypes() {
        try (Bash bash = Bash.builder().build()) {
            assertTrue(bash.exec("true").success());
            assertFalse(bash.exec("false").success());
            assertTrue(bash.exec("if true; then echo yes; fi").stdout().contains("yes"));
        }
    }

    @Test
    void byteArrayVfs() {
        byte[] data = {0, 1, 2, (byte) 0xff, 10};
        try (Bash bash = Bash.builder().build()) {
            bash.writeFile("/bin.dat", data);
            assertArrayEquals(data, bash.readFileBytes("/bin.dat"));
        }
    }

    @Test
    void builderTypes() {
        try (Bash bash = Bash.builder()
                .env("PROJECT", "bashkit")
                .file("/greet.txt", "hello")
                .username("tester")
                .hostname("box")
                .maxCommands(1000)
                .build()) {
            assertTrue(bash.exec("echo $PROJECT").stdout().contains("bashkit"));
            assertEquals("hello", bash.readFile("/greet.txt"));
            assertTrue(bash.exec("whoami").stdout().contains("tester"));
            assertTrue(bash.exec("hostname").stdout().contains("box"));
        }
    }

    @Test
    void exceptions() {
        assertThrows(BashException.class, () -> {
            try (Bash bash = Bash.builder().build()) {
                bash.execOrThrow("exit 1");
            }
        });
        // exec() keeps non-zero as a normal result
        try (Bash bash = Bash.builder().build()) {
            assertEquals(1, bash.exec("exit 1").exitCode());
        }
    }

    @Test
    void collections() {
        try (Bash bash = Bash.builder().build()) {
            String[] words = bash.exec("printf '%s\\n' a 'b c' d").stdout().split("\n");
            assertArrayEquals(new String[]{"a", "b c", "d"}, words);
        }
    }

    @Test
    void mapConfig() {
        try (Bash bash = Bash.builder().env("K", "v").build()) {
            Map<String, String> env = Map.of("K", "v");
            assertEquals(env.get("K"), bash.exec("echo $K").stdout().trim());
        }
    }
}
