package io.github.terseprompts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Host-directory mounts through the sandbox boundary. Each test skips unless
 * the loaded native library advertises the {@code realfs-mounts} capability,
 * so the suite stays green with pre-mount libraries.
 */
class HostMountTest {

    private static boolean mountsSupported() {
        try {
            return BashkitRuntime.supports("realfs-mounts");
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    void readOnlyMountReadsHostFilesAndBlocksWrites() throws Exception {
        assumeTrue(mountsSupported());
        Path host = Files.createTempDirectory("bashkit-mount-ro");
        Files.writeString(host.resolve("note.txt"), "host-bytes");
        try (Bash bash = Bash.builder()
                .allowMountsUnder(host.toString())
                .mount("/data", host.toString())
                .build()) {

            ExecResult r = bash.exec("cat /data/note.txt");
            assertEquals(0, r.exitCode());
            assertEquals("host-bytes", r.stdout().strip());

            ExecResult w = bash.exec("echo nope > /data/denied.txt");
            assertTrue(w.exitCode() != 0, "write to read-only mount must fail");
            assertFalse(Files.exists(host.resolve("denied.txt")), "host file must not appear");
        } finally {
            TestSupport.deleteRecursively(host);
        }
    }

    @Test
    void writableMountRoundTripsInBothDirections() throws Exception {
        assumeTrue(mountsSupported());
        Path host = Files.createTempDirectory("bashkit-mount-rw");
        try (Bash bash = Bash.builder()
                .allowMountsUnder(host.toString())
                .mount("/data", host.toString(), true)
                .build()) {

            bash.execOrThrow("printf 'from-sandbox' > /data/out.txt");
            assertEquals("from-sandbox", Files.readString(host.resolve("out.txt")));

            Files.writeString(host.resolve("in.txt"), "from-host");
            assertEquals("from-host", bash.readFile("/data/in.txt"));
        } finally {
            TestSupport.deleteRecursively(host);
        }
    }

    @Test
    void runtimeMountAndUnmountPreservesShellState() throws Exception {
        assumeTrue(mountsSupported());
        Path host = Files.createTempDirectory("bashkit-mount-rt");
        Files.writeString(host.resolve("f.txt"), "rt");
        try (Bash bash = Bash.builder()
                .allowMountsUnder(host.toString())
                .build()) {

            assertTrue(bash.exec("cat /mnt/f.txt").exitCode() != 0, "not mounted yet");

            bash.mount("/mnt", host.toString());
            assertEquals("rt", bash.readFile("/mnt/f.txt"));

            bash.execOrThrow("X=seen-by-unmount");
            bash.unmount("/mnt");
            assertTrue(bash.exec("cat /mnt/f.txt").exitCode() != 0, "unmounted");

            ExecResult keep = bash.exec("echo $X");
            assertEquals("seen-by-unmount", keep.stdout().strip());
        } finally {
            TestSupport.deleteRecursively(host);
        }
    }

    @Test
    void rejectsMountRootOutsideAllowlist() throws Exception {
        assumeTrue(mountsSupported());
        Path allowed = Files.createTempDirectory("bashkit-mount-allowed");
        Path outside = Files.createTempDirectory("bashkit-mount-outside");
        try (Bash bash = Bash.builder()
                .allowMountsUnder(allowed.toString())
                .build()) {

            BashException e = assertThrows(BashException.class,
                    () -> bash.mount("/data", outside.toString()));
            assertTrue(e.getMessage().contains("allowed_mount_paths"));
        } finally {
            TestSupport.deleteRecursively(allowed);
            TestSupport.deleteRecursively(outside);
        }
    }

    @Test
    void builderRejectsMountsWithoutAllowlist() throws Exception {
        assumeTrue(mountsSupported());
        Path host = Files.createTempDirectory("bashkit-mount-noallow");
        assertThrows(IllegalArgumentException.class,
                () -> Bash.builder().mount("/data", host.toString()).build());
        TestSupport.deleteRecursively(host);
    }

    @Test
    void traversalCannotEscapeMountRoot() throws Exception {
        assumeTrue(mountsSupported());
        Path host = Files.createTempDirectory("bashkit-mount-traverse");
        Path sibling = Files.createTempDirectory("bashkit-mount-sibling");
        Files.writeString(sibling.resolve("secret.txt"), "should-not-see");
        try (Bash bash = Bash.builder()
                .allowMountsUnder(host.toString())
                .mount("/data", host.toString())
                .build()) {

            ExecResult r = bash.exec("cat /data/../secret.txt 2>/dev/null");
            assertTrue(r.exitCode() != 0 || r.stdout().isBlank(),
                    "traversal must not reach outside the mount root");
            assertFalse(r.stdout().contains("should-not-see"));
        } finally {
            TestSupport.deleteRecursively(host);
            TestSupport.deleteRecursively(sibling);
        }
    }
}
