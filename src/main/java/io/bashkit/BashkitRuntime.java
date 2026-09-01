package io.bashkit;

import com.sun.jna.Native;

/** Loads the native bashkit library once and guards the ABI version. */
public final class BashkitRuntime {

    private static final class Holder {
        static final Bashkit LIB = load();
    }

    private static Bashkit load() {
        String p = System.getProperty("bashkit.native.path");
        if (p == null || p.isBlank()) p = System.getenv("BASHKIT_NATIVE_PATH");
        return (p != null && !p.isBlank())
                ? Native.load(p, Bashkit.class)
                : (Bashkit) Native.loadLibrary("bashkit", Bashkit.class);
    }

    private BashkitRuntime() {
    }

    public static Bashkit library() {
        return Holder.LIB;
    }

    /** Verifies {@code BASHKIT_ABI_VERSION_1}; throws on mismatch. */
    public static int abiVersion() {
        int v = Holder.LIB.bashkit_abi_version();
        if (v != 1) throw new IllegalStateException("bashkit ABI " + v + " != required 1");
        return v;
    }

    public static String version() {
        return str(Holder.LIB.bashkit_version());
    }

    public static String capabilitiesJson() {
        return str(Holder.LIB.bashkit_capabilities_json());
    }

    private static String str(Bashkit.BashkitBytes.ByValue v) {
        if (v == null || v.ptr == null || v.len == 0) return "";
        int n = (int) v.len;
        for (int i = 0; i < n; i++) if (v.ptr.getByte(i) == 0) { n = i; break; }
        return new String(v.ptr.getByteArray(0, n), java.nio.charset.StandardCharsets.UTF_8);
    }
}
