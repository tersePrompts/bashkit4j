package io.github.terseprompts;

/** Raised on ABI-level failure (not normal non-zero shell exit codes). */
public class BashException extends RuntimeException {
    public final int status;

    public BashException(int status, String message) {
        super(message);
        this.status = status;
    }
}
