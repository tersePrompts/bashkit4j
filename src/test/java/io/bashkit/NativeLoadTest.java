package io.bashkit;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class NativeLoadTest {

    @Test
    void currentPlatformBundledLibraryExists() {
        File lib = BashkitRuntime.bundledLibraryFile();
        // the current platform's bundled lib must resolve to a real file for the
        // platform matrix we ship (windows-x86_64 is bundled; others are optional)
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean supported = os.contains("win") || os.contains("linux") || os.contains("mac");
        if (supported && (arch.contains("amd64") || arch.contains("x86_64"))) {
            assertTrue(lib.isFile(), "bundled lib missing for current platform: " + lib);
        }
    }

    @Test
    void loadsViatAutoDetect() {
        // full load path (explicit-property fallback) must yield a working library
        assertTrue(BashkitRuntime.abiVersion() == 1);
        assertTrue(BashkitRuntime.version().length() > 0);
    }
}