package io.bashkit.sample;

import io.bashkit.Bash;
import io.bashkit.BashkitRuntime;
import io.bashkit.ExecResult;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal public demo of bashkit4j.
 * <p>
 * Run:
 * <pre>
 *   mvn -q package
 *   java -Dbashkit.native.path=native/windows-x86_64/bashkit.dll \
 *        -cp target/classes:$(path to jna jar) io.bashkit.sample.BashkitSample
 * </pre>
 * Or simply: {@code mvn -q exec:java -Dexec.mainClass=io.bashkit.sample.BashkitSample}
 * (requires the exec-maven-plugin / native path set).
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
