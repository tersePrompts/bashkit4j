package io.bashkit;

import com.sun.jna.Native;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the native bashkit library once and guards the ABI version.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>{@code -Dbashkit.native.path} / {@code BASHKIT_NATIVE_PATH} (explicit path);</li>
 *   <li>bundled library auto-detected for {@code <os>-<arch>}, first from the filesystem
 *       {@code native/<os>-<arch>/} next to the working dir, then from the classpath
 *       resource of the same name (extracted to a temp dir);</li>
 *   <li>the default system library path.</li>
 * </ol>
 */
public final class BashkitRuntime {

    private static final class Holder {
        static final Bashkit LIB = load();
    }

    private static Bashkit load() {
        String p = System.getProperty("bashkit.native.path");
        if (p == null || p.isBlank()) p = System.getenv("BASHKIT_NATIVE_PATH");
        if (p != null && !p.isBlank()) {
            return Native.load(p, Bashkit.class);
        }
        File bundled = bundledLibraryFile();
        if (bundled.isFile()) {
            return Native.load(bundled.getAbsolutePath(), Bashkit.class);
        }
        String resource = bundledResourceName();
        java.net.URL url = BashkitRuntime.class.getResource(resource);
        if (url != null) {
            try {
                Path tmp = Files.createTempFile("bashkit-", nativeName());
                try (InputStream in = url.openStream()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                return Native.load(tmp.toString(), Bashkit.class);
            } catch (Exception e) {
                throw new IllegalStateException("failed to extract bundled bashkit " + resource, e);
            }
        }
        return (Bashkit) Native.loadLibrary("bashkit", Bashkit.class);
    }

    /** Convenience for on-disk resolution; tests use the explicit path instead. */
    static File bundledLibraryFile() {
        return new File(resolveDir(), nativeName());
    }

    private static String resolveDir() {
        return "native" + File.separator + os() + "-" + arch() + File.separator;
    }

    private static String bundledResourceName() {
        return "/native/" + os() + "-" + arch() + "/" + nativeName();
    }

    private static String nativeName() {
        switch (os()) {
            case "windows":
                return "bashkit.dll";
            case "osx":
                return "libbashkit.dylib";
            default:
                return "libbashkit.so";
        }
    }

    private static String os() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) return "windows";
        if (osName.contains("mac") || osName.contains("darwin")) return "osx";
        if (osName.contains("linux")) return "linux";
        return osName.replaceAll("[^a-z0-9]", "");
    }

    private static String arch() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        if (a.contains("aarch64") || a.contains("arm64")) return "aarch64";
        if (a.contains("amd64") || a.contains("x86_64")) return "x86_64";
        return a.replaceAll("[^a-z0-9]", "");
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