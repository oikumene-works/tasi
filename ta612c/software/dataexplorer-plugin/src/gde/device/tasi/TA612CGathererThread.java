/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.exception.TimeOutException;
import java.io.IOException;
import java.util.List;
import java.util.function.LongSupplier;

/** Bounded live acquisition; the transport and listener also allow device-free tests. */
public final class TA612CGathererThread extends Thread {
    public interface Transport {
        void connect() throws Exception;
        void startLive() throws IOException;
        List<TA612CFrameDecoder.Sample> readSamples() throws IOException, TimeOutException;
        void close();
    }

    public interface Listener {
        /**
         * Relative time starts at zero for each probe-mask segment. Its epoch is
         * the acquisition's wall-clock anchor plus the segment's monotonic
         * offset, rounded to whole milliseconds. All-open frames end a segment
         * but do not create a RecordSet.
         */
        void onSample(TA612CFrameDecoder.Sample sample, double elapsedMs, long firstSampleEpochMs);
        void onStopped(String message, Exception failure);
    }

    private final Transport transport;
    private final Listener listener;
    private final long silenceNanos;
    private final int maxRestarts;
    private final LongSupplier nanoTime;
    private final LongSupplier wallTimeMillis;
    private volatile boolean stopRequested;

    public TA612CGathererThread(Transport transport, Listener listener) {
        this(transport, listener, 1500, 3);
    }

    // Shorter bounds are used only by transport lifecycle tests.
    TA612CGathererThread(Transport transport, Listener listener, long silenceMs, int maxRestarts) {
        this(transport, listener, silenceMs, maxRestarts, System::nanoTime, System::currentTimeMillis);
    }

    // Clock injection keeps time-model checks deterministic without a physical meter.
    TA612CGathererThread(Transport transport, Listener listener, long silenceMs, int maxRestarts,
            LongSupplier nanoTime, LongSupplier wallTimeMillis) {
        super("TA612C live acquisition");
        this.transport = transport;
        this.listener = listener;
        this.silenceNanos = silenceMs * 1_000_000L;
        this.maxRestarts = maxRestarts;
        this.nanoTime = nanoTime;
        this.wallTimeMillis = wallTimeMillis;
    }

    public void requestStop() {
        stopRequested = true;
        interrupt();
    }

    public boolean isStopRequested() { return stopRequested; }

    @Override
    public void run() {
        String message = "TA612C stopped. Collected data is retained.";
        Exception failure = null;
        long firstSampleNanos = 0;
        long firstSampleEpochMs = 0;
        boolean firstSample = true;
        long segmentStartNanos = 0;
        long segmentEpochMs = 0;
        int segmentMask = -1;
        try {
            if (stopRequested) return;
            transport.connect();
            if (stopRequested) return;
            transport.startLive();
            long lastActivity = nanoTime.getAsLong();
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
                    long now = nanoTime.getAsLong();
                    if (firstSample) {
                        firstSampleNanos = now;
                        firstSampleEpochMs = wallTimeMillis.getAsLong();
                        firstSample = false;
                    }
                    if (sample.validMask() != segmentMask) {
                        segmentMask = sample.validMask();
                        segmentStartNanos = now;
                        // Preserve gaps on the monotonic acquisition timeline, including
                        // all-open periods, without introducing wall-clock adjustments.
                        segmentEpochMs = firstSampleEpochMs
                                + Math.round((now - firstSampleNanos) / 1_000_000.0);
                    }
                    listener.onSample(sample, (now - segmentStartNanos) / 1_000_000.0, segmentEpochMs);
                    lastActivity = now;
                    restarts = 0;
                }
                if (stopRequested) break;
                if (nanoTime.getAsLong() - lastActivity >= silenceNanos) {
                    if (restarts == maxRestarts) {
                        throw new IOException("No valid TA612C live frame after " + maxRestarts + " restart attempts");
                    }
                    transport.startLive();
                    ++restarts;
                    lastActivity = nanoTime.getAsLong();
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
