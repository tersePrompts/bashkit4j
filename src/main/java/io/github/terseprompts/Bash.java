package io.github.terseprompts;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Sandboxed virtual bash backed by a native bashkit instance. Must be closed. */
public final class Bash implements AutoCloseable {

    private static final int OK = 0;
    private static final int EXECUTION_ERROR = 4;

    private final Bashkit lib;
    private final Pointer h;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private boolean closed;

    /** By-value {@code {ptr,len}} view for the JNA interface. */
    private static Bashkit.BashkitBytes.ByValue mem(byte[] b) {
        Bashkit.BashkitBytes.ByValue v = new Bashkit.BashkitBytes.ByValue();
        if (b.length == 0) return v;
        Memory m = new Memory(b.length);
        m.write(0, b, 0, b.length);
        v.ptr = m;
        v.len = b.length;
        v.write();
        return v;
    }

    private static Bashkit.BashkitBytes.ByValue utf8(String s) {
        return mem(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Bash(Bashkit lib, Pointer h) {
        this.lib = lib;
        this.h = h;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final java.util.LinkedHashMap<String, String> env = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, String> files = new java.util.LinkedHashMap<>();
        private final java.util.List<MountSpec> mounts = new java.util.ArrayList<>();
        private final java.util.List<String> allowedMountPrefixes = new java.util.ArrayList<>();
        private String cwd, username, hostname;
        private Long maxCommands;
        private Long timeoutMs;

        public Builder cwd(String c) { this.cwd = c; return this; }
        public Builder username(String u) { this.username = u; return this; }
        public Builder hostname(String n) { this.hostname = n; return this; }
        public Builder maxCommands(long n) { this.maxCommands = n; return this; }

        /**
         * Wall-clock execution timeout per {@link Bash#exec(String)} call. A
         * script that exceeds it throws {@link BashException} (the instance
         * stays usable). Checked at command boundaries, so a single long
         * command can overshoot slightly. Requires a native library that
         * accepts {@code limits.timeout_ms} (all bundled 0.3.0+ libraries do).
         */
        public Builder timeoutMs(long ms) { this.timeoutMs = ms; return this; }
        public Builder env(String k, String v) { this.env.put(k, v); return this; }
        public Builder file(String p, String c) { this.files.put(p, c); return this; }

        /** Mounts a host directory at {@code vfsPath}, read-only unless {@code writable}. */
        public Builder mount(String vfsPath, String hostRoot, boolean writable) {
            this.mounts.add(new MountSpec(vfsPath, hostRoot, writable));
            return this;
        }

        /** Mounts a host directory at {@code vfsPath}, read-only. */
        public Builder mount(String vfsPath, String hostRoot) {
            return mount(vfsPath, hostRoot, false);
        }

        /** Host path prefixes mounts may resolve under; required for any mount. */
        public Builder allowMountsUnder(String... prefixes) {
            for (String p : prefixes) {
                if (p != null && !p.isBlank()) this.allowedMountPrefixes.add(p);
            }
            return this;
        }

        public Bash build() {
            if (!mounts.isEmpty() && allowedMountPrefixes.isEmpty()) {
                throw new IllegalArgumentException(
                        "mounts require allowMountsUnder(...) — the sandbox stays closed otherwise");
            }
            StringBuilder j = new StringBuilder("{\"schema_version\":1");
            if (cwd != null) j.append(",\"cwd\":\"").append(esc(cwd)).append('"');
            if (username != null) j.append(",\"username\":\"").append(esc(username)).append('"');
            if (hostname != null) j.append(",\"hostname\":\"").append(esc(hostname)).append('"');
            if (!env.isEmpty()) j.append(",\"env\":").append(strMap(env));
            if (!files.isEmpty()) j.append(",\"files\":").append(strMap(files));
            if (maxCommands != null || timeoutMs != null) {
                j.append(",\"limits\":{");
                if (timeoutMs != null) j.append("\"timeout_ms\":").append(timeoutMs);
                if (maxCommands != null) {
                    if (timeoutMs != null) j.append(',');
                    j.append("\"max_commands\":").append(maxCommands);
                }
                j.append('}');
            }
            if (!mounts.isEmpty() || !allowedMountPrefixes.isEmpty()) {
                BashkitRuntime.requireMounts();
                if (!allowedMountPrefixes.isEmpty()) j.append(",\"allowed_mount_paths\":").append(strArray(allowedMountPrefixes));
                if (!mounts.isEmpty()) {
                    j.append(",\"mounts\":[");
                    for (int i = 0; i < mounts.size(); i++) {
                        MountSpec m = mounts.get(i);
                        if (i > 0) j.append(',');
                        j.append("{\"path\":\"").append(esc(m.vfsPath))
                         .append("\",\"root\":\"").append(esc(m.hostRoot))
                         .append("\",\"writable\":").append(m.writable).append('}');
                    }
                    j.append(']');
                }
            }
            j.append('}');

            Bashkit lib = BashkitRuntime.library();
            PointerByReference out = new PointerByReference();
            PointerByReference errRef = new PointerByReference();
            int st = lib.bashkit_create_json(utf8(j.toString()), out, errRef);
            if (st != OK) throw readErr(lib, errRef, st);
            return new Bash(lib, out.getValue());
        }

        private static String strMap(java.util.Map<String, String> m) {
            StringBuilder s = new StringBuilder("{");
            m.forEach((k, v) -> {
                if (s.length() > 1) s.append(',');
                s.append('"').append(esc(k)).append("\":\"").append(esc(v)).append('"');
            });
            return s.append('}').toString();
        }

        private static String strArray(java.util.List<String> values) {
            StringBuilder s = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) s.append(',');
                s.append('"').append(esc(values.get(i))).append('"');
            }
            return s.append(']').toString();
        }

        private static String esc(String x) {
            return x.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        }

        private record MountSpec(String vfsPath, String hostRoot, boolean writable) {
        }
    }

    public ExecResult exec(String script) {
        return execute(script, false);
    }

    public ExecResult execOrThrow(String script) {
        return execute(script, true);
    }

    private ExecResult execute(String script, boolean throwOnNonZero) {
        PointerByReference resRef = new PointerByReference();
        PointerByReference errRef = new PointerByReference();
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_execute(h, utf8(script), resRef, errRef);
            if (st != OK) throw readErr(errRef, st);
        } finally {
            lock.readLock().unlock();
        }
        Pointer res = resRef.getValue();
        try {
            int flags = lib.bashkit_result_flags(res);
            ExecResult r = new ExecResult(
                    bytes(lib.bashkit_result_stdout(res)),
                    new String(bytes(lib.bashkit_result_stderr(res)), java.nio.charset.StandardCharsets.UTF_8),
                    lib.bashkit_result_exit_code(res),
                    (flags & 1) != 0,
                    (flags & 2) != 0,
                    new String(bytes(lib.bashkit_result_final_env_json(res)), java.nio.charset.StandardCharsets.UTF_8));
            if (throwOnNonZero && r.exitCode() != 0) {
                throw new BashException(EXECUTION_ERROR,
                        "script exited code " + r.exitCode() + ": " + r.stderr());
            }
            return r;
        } finally {
            lib.bashkit_result_free(res);
        }
    }

    private static byte[] bytes(Bashkit.BashkitBytes.ByValue v) {
        if (v == null || v.ptr == null || v.len == 0) return new byte[0];
        return v.ptr.getByteArray(0, (int) v.len);
    }

    /**
     * Requests cancellation of a running execution. Safe to call from any
     * thread, including while another thread is blocked in
     * {@link #exec(String)}: the abort lands at the next command boundary and
     * that {@code exec} call throws {@link BashException} with
     * {@code status == Bashkit.STATUS_CANCELLED}.
     * <p>
     * The flag is sticky: until {@link #clearCancel()} is called (or this
     * instance is discarded), every subsequent {@code exec} aborts immediately.
     *
     * @throws UnsupportedOperationException
     *         when the loaded native library lacks the {@code cancellation}
     *         capability
     */
    public void cancel() {
        BashkitRuntime.requireCancellation();
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_cancel(h);
            if (st != OK) throw new BashException(st, "bashkit_cancel failed with status " + st);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clears the cancellation flag set by {@link #cancel()} so this instance
     * can run scripts again without discarding shell or VFS state. Call it
     * once the in-flight cancelled execution has finished.
     */
    public void clearCancel() {
        BashkitRuntime.requireCancellation();
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_clear_cancel(h);
            if (st != OK) throw new BashException(st, "bashkit_clear_cancel failed with status " + st);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Mounts a host directory at {@code vfsPath} into this running session, read-only. */
    public void mount(String vfsPath, String hostRoot) {
        mount(vfsPath, hostRoot, false);
    }

    /**
     * Mounts a host directory at {@code vfsPath} into this running session.
     * The host root must resolve under a prefix passed to
     * {@link Builder#allowMountsUnder(String...)} at build time.
     */
    public void mount(String vfsPath, String hostRoot, boolean writable) {
        BashkitRuntime.requireMounts();
        PointerByReference errRef = new PointerByReference();
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_mount(h, utf8(vfsPath), utf8(hostRoot), writable ? 1 : 0, errRef);
            if (st != OK) throw readErr(errRef, st);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Removes the mount at {@code vfsPath}; shell state is preserved. */
    public void unmount(String vfsPath) {
        PointerByReference errRef = new PointerByReference();
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_unmount(h, utf8(vfsPath), errRef);
            if (st != OK) throw readErr(errRef, st);
        } finally {
            lock.readLock().unlock();
        }
    }

    private BashException readErr(PointerByReference errRef, int st) {
        return readErr(lib, errRef, st);
    }

    private static BashException readErr(Bashkit lib, PointerByReference errRef, int st) {
        String msg = "";
        Pointer e = errRef.getValue();
        if (e != null) {
            msg = new String(bytes(lib.bashkit_error_message(e)), java.nio.charset.StandardCharsets.UTF_8);
            lib.bashkit_error_free(e);
        }
        return new BashException(st, msg.isEmpty() ? "bashkit status " + st : msg);
    }

    public void writeFile(String path, byte[] content) {
        PointerByReference errRef = new PointerByReference();
        byte[] data = content == null ? new byte[0] : content;
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_write_file(h, utf8(path), mem(data), errRef);
            if (st != OK) throw readErr(errRef, st);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void writeFile(String path, String content) {
        writeFile(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public byte[] readFileBytes(String path) {
        PointerByReference bufRef = new PointerByReference();
        PointerByReference errRef = new PointerByReference();
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_read_file(h, utf8(path), bufRef, errRef);
            if (st != OK) throw readErr(errRef, st);
        } finally {
            lock.readLock().unlock();
        }
        Pointer buf = bufRef.getValue();
        try {
            return bytes(lib.bashkit_buffer_bytes(buf));
        } finally {
            lib.bashkit_buffer_free(buf);
        }
    }

    public String readFile(String path) {
        return new String(readFileBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    public void mkdir(String path, boolean recursive) {
        PointerByReference errRef = new PointerByReference();
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_mkdir(h, utf8(path), recursive ? 1 : 0, errRef);
            if (st != OK) throw readErr(errRef, st);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void remove(String path, boolean recursive) {
        PointerByReference errRef = new PointerByReference();
        lock.readLock().lock();
        try {
            checkOpen();
            int st = lib.bashkit_remove(h, utf8(path), recursive ? 1 : 0, errRef);
            if (st != OK) throw readErr(errRef, st);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Bash closed");
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (!closed) {
                closed = true;
                lib.bashkit_free(h);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
