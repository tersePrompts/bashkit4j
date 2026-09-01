package io.bashkit;

/**
 * Result of a shell execution. Non-zero exit code is a normal shell outcome,
 * not an exception.
 */
public record ExecResult(
        byte[] stdoutBytes,
        String stderr,
        int exitCode,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        String finalEnvJson) {

    public boolean success() {
        return exitCode == 0;
    }

    /** stdout decoded as UTF-8 (binary data should use {@link #stdoutBytes()}). */
    public String stdout() {
        return new String(stdoutBytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
