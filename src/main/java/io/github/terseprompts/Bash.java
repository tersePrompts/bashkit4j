package io.github.terseprompts;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

/** Sandboxed virtual bash backed by a native bashkit instance. Must be closed. */
public final class Bash implements AutoCloseable {

    private static final int OK = 0;
    private static final int EXECUTION_ERROR = 4;

    private final Bashkit lib;
    private final Pointer h;
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
        private String cwd, username, hostname;
        private Long maxCommands;

        public Builder cwd(String c) { this.cwd = c; return this; }
        public Builder username(String u) { this.username = u; return this; }
        public Builder hostname(String n) { this.hostname = n; return this; }
        public Builder maxCommands(long n) { this.maxCommands = n; return this; }
        public Builder env(String k, String v) { this.env.put(k, v); return this; }
        public Builder file(String p, String c) { this.files.put(p, c); return this; }

        public Bash build() {
            StringBuilder j = new StringBuilder("{\"schema_version\":1");
            if (cwd != null) j.append(",\"cwd\":\"").append(esc(cwd)).append('"');
            if (username != null) j.append(",\"username\":\"").append(esc(username)).append('"');
            if (hostname != null) j.append(",\"hostname\":\"").append(esc(hostname)).append('"');
            if (!env.isEmpty()) j.append(",\"env\":").append(strMap(env));
            if (!files.isEmpty()) j.append(",\"files\":").append(strMap(files));
            if (maxCommands != null) j.append(",\"limits\":{\"max_commands\":").append(maxCommands).append('}');
            j.append('}');

            Bashkit lib = BashkitRuntime.library();
            PointerByReference out = new PointerByReference();
            int st = lib.bashkit_create_json(utf8(j.toString()), out, null);
            if (st != OK) throw new BashException(st, "bashkit status " + st);
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

        private static String esc(String x) {
            return x.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
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
        synchronized (this) {
            checkOpen();
            int st = lib.bashkit_execute(h, utf8(script), resRef, errRef);
            if (st != OK) throw readErr(errRef, st);
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

    private BashException readErr(PointerByReference errRef, int st) {
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
        synchronized (this) {
            checkOpen();
            int st = lib.bashkit_write_file(h, utf8(path), mem(data), errRef);
            if (st != OK) throw readErr(errRef, st);
        }
    }

    public void writeFile(String path, String content) {
        writeFile(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public byte[] readFileBytes(String path) {
        PointerByReference bufRef = new PointerByReference();
        PointerByReference errRef = new PointerByReference();
        synchronized (this) {
            checkOpen();
            int st = lib.bashkit_read_file(h, utf8(path), bufRef, errRef);
            if (st != OK) throw readErr(errRef, st);
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
        synchronized (this) {
            checkOpen();
            int st = lib.bashkit_mkdir(h, utf8(path), recursive ? 1 : 0, errRef);
            if (st != OK) throw readErr(errRef, st);
        }
    }

    public void remove(String path, boolean recursive) {
        PointerByReference errRef = new PointerByReference();
        synchronized (this) {
            checkOpen();
            int st = lib.bashkit_remove(h, utf8(path), recursive ? 1 : 0, errRef);
            if (st != OK) throw readErr(errRef, st);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Bash closed");
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!closed) {
                closed = true;
                lib.bashkit_free(h);
            }
        }
    }
}
