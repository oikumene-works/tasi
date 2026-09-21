/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.exception.TimeOutException;
import java.io.IOException;
import java.util.List;

/**
 * Owns a live acquisition's connect/request/read/close lifecycle off the SWT
 * thread. Time is host receipt time, not device time. The existing live policy
 * permits a bounded number of repeated live requests after silence; this is
 * distinct from REC, which never retries a download automatically. Transport
 * and listener seams allow deterministic device-free lifecycle tests.
 */
public final class TA612CGathererThread extends Thread {
    /** Serial operations used by this worker; close must tolerate failed opens. */
    public interface Transport {
        /** Acquires exclusive ownership and opens the configured connection. */
        void connect() throws Exception;
        /** Sends the live request, initially or for a bounded silence restart. */
        void startLive() throws IOException;
        /** Returns decoded samples; an empty list or timeout gives no new sample. */
        List<TA612CFrameDecoder.Sample> readSamples() throws IOException, TimeOutException;
        /** Closes the owned transport before the terminal callback is delivered. */
        void close();
    }

    /** Worker-thread callbacks; UI implementations must marshal SWT work. */
    public interface Listener {
        /**
         * Receives every valid frame, including all-open frames. Elapsed time is
         * monotonic milliseconds from the first decoded frame; its wall-clock
         * epoch is shared by all subsequent probe-availability segments.
         */
        void onSample(TA612CFrameDecoder.Sample sample, double elapsedMs, long firstSampleEpochMs);
        /** Reports termination after attempted cleanup, including close failures. */
        void onStopped(String message, Exception failure);
    }

    private final Transport transport;
    private final Listener listener;
    private final long silenceNanos;
    private final int maxRestarts;
    private volatile boolean stopRequested;

    /** Configures the accepted live policy: 1.5 s silence and three restarts. */
    public TA612CGathererThread(Transport transport, Listener listener) {
        this(transport, listener, 1500, 3);
    }

    /** Injects silence/restart bounds for tests without changing the wire command. */
    TA612CGathererThread(Transport transport, Listener listener, long silenceMs, int maxRestarts) {
        super("TA612C live acquisition");
        this.transport = transport;
        this.listener = listener;
        this.silenceNanos = silenceMs * 1_000_000L;
        this.maxRestarts = maxRestarts;
    }

    /** Requests cooperative cancellation and wakes sleeps; the worker owns close. */
    public void requestStop() {
        stopRequested = true;
        interrupt();
    }

    public boolean isStopRequested() { return stopRequested; }

    /**
     * Reads until stopped or failed, retaining data already delivered to the
     * listener. Any valid sample resets the silence-restart count, even when
     * all probes are open. Timeouts count as silence here; other read failures
     * terminate acquisition. Cleanup also runs after cancellation before open.
     */
    @Override
    public void run() {
        String message = "TA612C stopped. Collected data is retained.";
        Exception failure = null;
        long firstSampleNanos = 0;
        long firstSampleEpochMs = 0;
        boolean firstSample = true;
        try {
            if (stopRequested) return;
            transport.connect();
            if (stopRequested) return;
            transport.startLive();
            long lastActivity = System.nanoTime();
            int restarts = 0;
            while (!stopRequested) {
                List<TA612CFrameDecoder.Sample> samples;
                try {
                    samples = transport.readSamples();
                } catch (TimeOutException timeout) {
                    samples = List.of();
                }
                for (TA612CFrameDecoder.Sample sample : samples) {
                    if (stopRequested) break;
                    long now = System.nanoTime();
                    if (firstSample) {
                        firstSampleNanos = now;
                        firstSampleEpochMs = System.currentTimeMillis();
                        firstSample = false;
                    }
                    listener.onSample(sample, (now - firstSampleNanos) / 1_000_000.0, firstSampleEpochMs);
                    lastActivity = now;
                    restarts = 0;
                }
                if (stopRequested) break;
                if (System.nanoTime() - lastActivity >= silenceNanos) {
                    if (restarts == maxRestarts) {
                        throw new IOException("No valid TA612C live frame after " + maxRestarts + " restart attempts");
                    }
                    transport.startLive();
                    ++restarts;
                    lastActivity = System.nanoTime();
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException interrupted) {
            if (!stopRequested) {
                failure = interrupted;
                message = "TA612C acquisition was interrupted. Collected data is retained.";
            }
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (!stopRequested) {
                failure = error;
                message = "TA612C stopped: " + error.getMessage() + ". Collected data is retained.";
            }
        } finally {
            stopRequested = true;
            try {
                transport.close();
            } catch (RuntimeException closeError) {
                if (failure == null) failure = closeError;
                else failure.addSuppressed(closeError);
                message += " The port reported a close error.";
            }
            listener.onStopped(message, failure);
        }
    }
}
