package io.github.terseprompts.sample;

import io.github.terseprompts.Bash;
import io.github.terseprompts.BashkitRuntime;
import io.github.terseprompts.ExecResult;

/**
 * Minimal public demo of bashkit4j.
 * <p>
 * Run (auto-detects the bundled native lib for your OS/arch):
 * <pre>
 *   mvn -q package
 *   java -cp target/bashkit4j-0.1.0-SNAPSHOT.jar:$(path to jna jar) \
 *        io.github.terseprompts.sample.BashkitSample
 * </pre>
 * Or simply: {@code mvn -q exec:java} (native path set automatically).
 */
public class BashkitSample {

    public static void main(String[] args) throws Exception {
        BashkitRuntime.library();
        System.out.println("bashkit version : " + BashkitRuntime.version());
        System.out.println("abi version     : " + BashkitRuntime.abiVersion());

        try (Bash bash = Bash.builder()
                .username("agent")
                .hostname("sandbox")
                .file("/notes.txt", "hello\nworld\n")
                .build()) {

            ExecResult hi = bash.exec("echo hello bashkit");
            print("echo", hi);

            ExecResult who = bash.exec("whoami; hostname");
            print("whoami/hostname", who);

            ExecResult sorted = bash.exec("sort /notes.txt | tr a-z A-Z");
            print("sort|tr", sorted);

            String expr = bash.exec("echo $((6 * 7))").stdout().trim();
            System.out.println("arithmetic      : " + expr);

            System.out.println("readFile        : " + bash.readFile("/notes.txt").replace("\n", "\\n"));

            if (BashkitRuntime.supports("realfs-mounts")) {
                java.nio.file.Path host = java.nio.file.Files.createTempDirectory("bashkit-demo");
                java.nio.file.Files.writeString(host.resolve("host.txt"), "i-am-on-your-disk");
                try (Bash mounted = Bash.builder()
                        .allowMountsUnder(host.toString())
                        .mount("/data", host.toString())
                        .build()) {
                    ExecResult cat = mounted.exec("cat /data/host.txt");
                    print("host mount (ro)", cat);
                    ExecResult write = mounted.exec("echo blocked > /data/nope.txt");
                    System.out.printf("%-16s: host file present=%b (expected false)%n",
                            "ro write denied", java.nio.file.Files.exists(host.resolve("nope.txt")));
                    System.out.println("write exit       : " + write.exitCode());
                } finally {
                    try (java.util.stream.Stream<java.nio.file.Path> paths =
                                 java.nio.file.Files.walk(host)) {
                        paths.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) { } });
                    } catch (Exception ignored) {
                    }
                }
            } else {
                System.out.println("host mounts     : not supported by this native lib");
            }
        }
        System.out.println("OK");
    }

    private static void print(String label, ExecResult r) {
        System.out.printf("%-16s: stdout=%s exit=%d%n",
                label, r.stdout().replace("\n", "\\n"), r.exitCode());
    }
}
