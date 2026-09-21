/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects one read-only REC transfer off the UI thread, with no automatic retry.
 * Samples remain private to the worker until framing and cleanup have succeeded.
 * Byte silence defines a receive endpoint only: a successful result cannot
 * prove that a whole frame was not lost or that the entire memory was received.
 */
public final class TA612CRecDownloader extends Thread {
    /** Bounded serial operations; deliberately exposes no recording or erase API. */
    public interface Transport {
        /** Acquires exclusive ownership and opens the configured connection. */
        void connect() throws Exception;
        /** Sends exactly one read-only REC request for this worker. */
        void startRec() throws IOException;
        /** Returns currently available bytes; throws on read failure or timeout. */
        byte[] readRecBytes() throws Exception;
        /** Attempts cleanup even when opening failed or cancellation came early. */
        void close();
    }
    /** Completion is delivered on the worker thread; marshal any UI operations. */
    public interface Listener {
        /**
         * Called after attempted close. Cancellation or failure forces a null
         * result, preventing publication of any accumulated partial transfer.
         */
        void onFinished(Result result, Exception failure, boolean cancelled);
    }
    /**
     * Structurally accepted transfer awaiting REC import validation. Frame counts
     * are observed locally; quietMs is the configured silence threshold, not an
     * exact elapsed duration or a device-supplied completion acknowledgement.
     */
    public record Result(List<TA612CRecDecoder.Sample> samples, int frames, int emptyFrames, long quietMs) {
        /** Freezes the sample list before it crosses from the worker to the UI. */
        public Result { samples = List.copyOf(samples); }
        /** Supplies the completeness caveat persisted in each imported segment. */
        public String completionDescription() {
            return "Transfer ended after " + quietMs + " ms without bytes; completeness unverified. "
                    + "No sample count, sequence numbers or proven end marker available.";
        }
    }
    private final Transport transport;
    private final Listener listener;
    private final long firstByteMs, quietMs, maximumMs;
    private final int maximumSamples;
    private volatile boolean cancelled;

    /**
     * Uses 5 s first-byte and 1.5 s inter-byte silence bounds, plus 5 minutes and
     * 100000 groups as resource safeguards, not claims about device capacity.
     */
    public TA612CRecDownloader(Transport transport, Listener listener) {
        this(transport, listener, 5000, 1500, 300000, 100000);
    }
    /** Injects shorter transfer bounds for device-free tests. */
    TA612CRecDownloader(Transport transport, Listener listener, long firstByteMs, long quietMs, long maximumMs, int maximumSamples) {
        super("TA612C REC download");
        this.transport = transport; this.listener = listener;
        this.firstByteMs = firstByteMs; this.quietMs = quietMs; this.maximumMs = maximumMs;
        this.maximumSamples = maximumSamples;
    }
    /** Cancels cooperatively; never closes a worker-owned port from the UI thread. */
    public void requestStop() { cancelled = true; interrupt(); }
    public boolean isStopRequested() { return cancelled; }

    /**
     * Accumulates validated groups until the byte-silence endpoint or a failure.
     * Every byte extends the quiet deadline, including partial and empty frames.
     * No initial response is an error. A read timeout is also fatal because the
     * core may have consumed bytes, making continuity unknown. Size, duration,
     * cancellation and close errors all suppress the pending result.
     */
    @Override public void run() {
        Result result = null;
        Exception failure = null;
        try {
            if (cancelled) return;
            transport.connect();
            if (cancelled) return;
            transport.startRec();
            long start = System.nanoTime(), lastByte = start;
            boolean received = false;
            TA612CRecDecoder decoder = new TA612CRecDecoder();
            List<TA612CRecDecoder.Sample> samples = new ArrayList<>();
            while (!cancelled) {
                // A read timeout may have discarded bytes in the core: it is fatal, not silence.
                byte[] bytes = transport.readRecBytes();
                long now = System.nanoTime();
                if (now - start >= maximumMs * 1_000_000L) throw new IOException("REC transfer duration limit exceeded");
                if (bytes.length != 0) {
                    received = true; lastByte = now;
                    samples.addAll(decoder.accept(bytes));
                    if (samples.size() > maximumSamples || decoder.frames() > 200000)
                        throw new IOException("REC transfer size limit exceeded");
                } else if (!received && now - start >= firstByteMs * 1_000_000L) {
                    throw new IOException("No REC response; empty memory cannot be inferred from silence");
                } else if (received && now - lastByte >= quietMs * 1_000_000L) {
                    decoder.endOfInput();
                    result = new Result(samples, decoder.frames(), decoder.emptyFrames(), quietMs);
                    break;
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            if (!cancelled) failure = e;
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!cancelled) failure = e;
        } finally {
            try { transport.close(); }
            catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            if (cancelled || failure != null) result = null;
            listener.onFinished(result, failure, cancelled);
        }
    }
}
