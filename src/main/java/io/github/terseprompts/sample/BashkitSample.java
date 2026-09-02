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
        }
        System.out.println("OK");
    }

    private static void print(String label, ExecResult r) {
        System.out.printf("%-16s: stdout=%s exit=%d%n",
                label, r.stdout().replace("\n", "\\n"), r.exitCode());
    }
}
