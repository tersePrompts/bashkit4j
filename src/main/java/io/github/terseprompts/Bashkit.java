package io.github.terseprompts;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.List;

/**
 * Direct 1:1 mapping of the {@code bashkit-capi} C ABI (version 1).
 * <p>
 * Thread-safety follows the C contract: calls against a single {@code Bashkit*}
 * instance are serialized by the caller; independent instances may run in
 * parallel. Ownership of every opaque object lives in the native library and
 * must be released with the matching {@code *_free} function.
 */
public interface Bashkit extends Library {

    /** ABI status: execution was aborted via {@link #bashkit_cancel(Pointer)}. */
    int STATUS_CANCELLED = 7;

    /**
     * {@code { const uint8_t *ptr; size_t len; }} passed/returned by value.
     * <p>
     * On 64-bit platforms both fields are 8 bytes wide; a {@code size_t} maps to
     * a 64-bit {@code long} on x64 (NOT the 32-bit Windows {@code long}).
     */
    class BashkitBytes extends Structure {
        public Pointer ptr;
        public long len;

        public BashkitBytes() {
        }

        public static final class ByValue extends BashkitBytes implements Structure.ByValue {
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("ptr", "len");
        }
    }

    /* ---- Static views (valid for process lifetime) ---- */

    int bashkit_abi_version();

    BashkitBytes.ByValue bashkit_version();

    BashkitBytes.ByValue bashkit_capabilities_json();

    /* ---- Lifecycle ---- */

    int bashkit_create_default(
            com.sun.jna.ptr.PointerByReference out_bash,
            com.sun.jna.ptr.PointerByReference out_error);

    int bashkit_create_json(
            BashkitBytes.ByValue config_json,
            com.sun.jna.ptr.PointerByReference out_bash,
            com.sun.jna.ptr.PointerByReference out_error);

    void bashkit_free(Pointer bash);

    int bashkit_execute(
            Pointer bash,
            BashkitBytes.ByValue script,
            com.sun.jna.ptr.PointerByReference out_result,
            com.sun.jna.ptr.PointerByReference out_error);

    /**
     * Requests cancellation of the running execution (requires the
     * {@code cancellation} capability). Lock-free: safe to call from any thread,
     * including while {@link #bashkit_execute} is blocked on the same handle.
     */
    int bashkit_cancel(Pointer bash);

    /** Clears the flag set by {@link #bashkit_cancel(Pointer)} so execution can resume. */
    int bashkit_clear_cancel(Pointer bash);

    /* ---- Result ---- */

    int bashkit_result_exit_code(Pointer result);

    BashkitBytes.ByValue bashkit_result_stdout(Pointer result);

    BashkitBytes.ByValue bashkit_result_stderr(Pointer result);

    int bashkit_result_flags(Pointer result);

    BashkitBytes.ByValue bashkit_result_final_env_json(Pointer result);

    void bashkit_result_free(Pointer result);

    /* ---- Virtual filesystem ---- */

    int bashkit_write_file(
            Pointer bash,
            BashkitBytes.ByValue path,
            BashkitBytes.ByValue content,
            com.sun.jna.ptr.PointerByReference out_error);

    int bashkit_read_file(
            Pointer bash,
            BashkitBytes.ByValue path,
            com.sun.jna.ptr.PointerByReference out_buffer,
            com.sun.jna.ptr.PointerByReference out_error);

    int bashkit_mkdir(
            Pointer bash,
            BashkitBytes.ByValue path,
            int recursive,
            com.sun.jna.ptr.PointerByReference out_error);

    int bashkit_remove(
            Pointer bash,
            BashkitBytes.ByValue path,
            int recursive,
            com.sun.jna.ptr.PointerByReference out_error);

    /* ---- Host mounts (requires the realfs-mounts capability) ---- */

    int bashkit_mount(
            Pointer bash,
            BashkitBytes.ByValue vfs_path,
            BashkitBytes.ByValue host_root,
            int writable,
            com.sun.jna.ptr.PointerByReference out_error);

    int bashkit_unmount(
            Pointer bash,
            BashkitBytes.ByValue vfs_path,
            com.sun.jna.ptr.PointerByReference out_error);

    /* ---- Buffer / Error ---- */

    BashkitBytes.ByValue bashkit_buffer_bytes(Pointer buffer);

    void bashkit_buffer_free(Pointer buffer);

    int bashkit_error_code(Pointer error);

    BashkitBytes.ByValue bashkit_error_message(Pointer error);

    void bashkit_error_free(Pointer error);
}
