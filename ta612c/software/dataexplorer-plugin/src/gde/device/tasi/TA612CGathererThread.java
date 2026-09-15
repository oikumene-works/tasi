/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.exception.TimeOutException;
import java.io.IOException;
import java.util.List;

/** Bounded live acquisition; the transport and listener also allow device-free tests. */
public final class TA612CGathererThread extends Thread {
    public interface Transport {
        void connect() throws Exception;
        void startLive() throws IOException;
        List<TA612CFrameDecoder.Sample> readSamples() throws IOException, TimeOutException;
        void close();
    }

    public interface Listener {
        void onSample(TA612CFrameDecoder.Sample sample, double elapsedMs, long firstSampleEpochMs);
        void onStopped(String message, Exception failure);
    }

    private final Transport transport;
    private final Listener listener;
    private final long silenceNanos;
    private final int maxRestarts;
    private volatile boolean stopRequested;

    public TA612CGathererThread(Transport transport, Listener listener) {
        this(transport, listener, 1500, 3);
    }

    // Shorter bounds are used only by transport lifecycle tests.
    TA612CGathererThread(Transport transport, Listener listener, long silenceMs, int maxRestarts) {
        super("TA612C live acquisition");
        this.transport = transport;
        this.listener = listener;
        this.silenceNanos = silenceMs * 1_000_000L;
        this.maxRestarts = maxRestarts;
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
