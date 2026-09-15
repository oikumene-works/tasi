/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** One read-only download command. A result is provisional: silence is not a proven end marker. */
public final class TA612CRecDownloader extends Thread {
    public interface Transport {
        void connect() throws Exception;
        void startRec() throws IOException;
        byte[] readRecBytes() throws Exception;
        void close();
    }
    public interface Listener { void onFinished(Result result, Exception failure, boolean cancelled); }
    public record Result(List<TA612CRecDecoder.Sample> samples, int frames, int emptyFrames, long quietMs) {
        public Result { samples = List.copyOf(samples); }
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

    public TA612CRecDownloader(Transport transport, Listener listener) {
        this(transport, listener, 5000, 1500, 300000, 100000);
    }
    TA612CRecDownloader(Transport transport, Listener listener, long firstByteMs, long quietMs, long maximumMs, int maximumSamples) {
        super("TA612C REC download");
        this.transport = transport; this.listener = listener;
        this.firstByteMs = firstByteMs; this.quietMs = quietMs; this.maximumMs = maximumMs;
        this.maximumSamples = maximumSamples;
    }
    public void requestStop() { cancelled = true; interrupt(); }
    public boolean isStopRequested() { return cancelled; }

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
