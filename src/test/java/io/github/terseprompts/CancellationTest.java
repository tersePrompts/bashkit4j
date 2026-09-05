package io.github.terseprompts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two resource controls beyond {@code maxCommands}: wall-clock timeout and
 * cancellation. Both abort at command boundaries and surface as
 * {@link BashException} — timeout keeps status 4 (execution error), a
 * cancelled script reports status 7 (cancelled).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CancellationTest {

    /** Long enough that only the timeout can end it; the deadline interrupts a pending sleep. */
    private static final String LONG_SLEEP = "sleep 30000";

    /**
     * Reaches a command boundary every second, so a cancel lands within ~1s
     * while burning almost no budget. Cancellation is not checked mid-command,
     * and tight loops race the profile's command/iteration caps — so a plain
     * spin loop is unsuitable for cancel tests.
     */
    private static final String SLEEP_LOOP = "while true; do sleep 1; done";

    @Test
    void timeoutMsAbortsRunawayScript() {
        try (Bash b = Bash.builder().timeoutMs(500).build()) {
            BashException e = assertThrows(BashException.class, () -> b.exec(LONG_SLEEP));
            assertTrue(e.getMessage().toLowerCase().contains("timeout"),
                    "expected a timeout message, got: " + e.getMessage());
        }
    }

    @Test
    void instanceIsReusableAfterTimeout() {
        try (Bash b = Bash.builder().timeoutMs(500).build()) {
            assertThrows(BashException.class, () -> b.exec(LONG_SLEEP));
            assertEquals("ok", b.exec("echo ok").stdout().trim());
        }
    }

    @Test
    void timeoutAndMaxCommandsCombine() {
        try (Bash b = Bash.builder().timeoutMs(10_000).maxCommands(3).build()) {
            // both limits parse together; max_commands trips first on this script
            assertThrows(BashException.class, () -> b.exec("echo 1; echo 2; echo 3; echo 4"));
        }
    }

    @Test
    void cancelAbortsRunningScript() throws Exception {
        try (Bash b = Bash.builder().build()) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    b.exec(SLEEP_LOOP);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            worker.start();
            Thread.sleep(500); // let the worker enter the native exec
            b.cancel();
            worker.join(10_000);
            assertFalse(worker.isAlive(), "exec did not return after cancel()");

            BashException e = assertInstanceOf(BashException.class, failure.get());
            assertEquals(Bashkit.STATUS_CANCELLED, e.status,
                    "expected the cancelled status, got: " + e.status + " " + e.getMessage());
            assertTrue(e.getMessage().toLowerCase().contains("cancel"),
                    "expected a cancellation message, got: " + e.getMessage());

            // the flag is sticky: the next exec aborts until clearCancel()
            assertThrows(BashException.class, () -> b.exec("echo hi"));
            b.clearCancel();
            assertEquals("hi", b.exec("echo hi").stdout().trim());
        }
    }

    @Test
    void cancelBeforeExecStillAbortsThatExec() throws Exception {
        try (Bash b = Bash.builder().build()) {
            b.cancel();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    b.exec("echo hi");
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            worker.start();
            worker.join(10_000);
            BashException e = assertInstanceOf(BashException.class, failure.get());
            assertEquals(Bashkit.STATUS_CANCELLED, e.status);
        }
    }
}
