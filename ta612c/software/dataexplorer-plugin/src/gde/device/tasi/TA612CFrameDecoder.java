/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Incremental live-stream decoder with a single 13-byte pending-frame buffer.
 * Live data can recover from noise by searching for the next valid frame; REC
 * deliberately uses a different, strict decoder because skipped samples would
 * corrupt its inferred time axis. Instances belong to one acquisition worker
 * and are not thread-safe. This class performs no serial or UI operations.
 */
public final class TA612CFrameDecoder {
    public static final int FRAME_SIZE = 13;
    public static final int PROBE_COUNT = 4;
    public static final int OPEN_PROBE_RAW = 28000;
    private final byte[] pending = new byte[FRAME_SIZE];
    private int length;
    private long rejectedFrames;

    /** Returns a fresh copy of the live request, including its wire checksum. */
    public static byte[] liveCommand() {
        return new byte[] {(byte) 0xAA, 0x55, 0x01, 0x03, 0x03};
    }

    /**
     * Accepts an arbitrary serial chunk and returns complete, validated samples.
     * A partial header/frame survives across calls. Invalid prefixes slide by
     * one byte so a header embedded in corrupt input can still be recovered.
     * The rejection counter counts bad checksums on complete candidates, not
     * every discarded noise byte. All-open frames are valid activity samples.
     */
    public List<Sample> accept(byte[] bytes) {
        List<Sample> samples = new ArrayList<>();
        for (byte next : bytes) {
            pending[length++] = next;
            while (length > 0) {
                boolean badPrefix = pending[0] != 0x55
                        || (length >= 2 && pending[1] != (byte) 0xAA)
                        || (length >= 3 && pending[2] != 0x01)
                        || (length >= 4 && pending[3] != 0x0B);
                if (badPrefix) {
                    discardFirst();
                } else if (length == FRAME_SIZE) {
                    if (checksumValid(pending)) {
                        samples.add(decode(pending));
                        length = 0;
                    } else {
                        ++rejectedFrames;
                        // Shift only one byte: a valid header may start inside bad input.
                        discardFirst();
                    }
                } else {
                    break;
                }
            }
        }
        return samples;
    }

    /** Drops an incomplete frame before a new request; retains diagnostic counts. */
    public void reset() {
        length = 0;
    }

    public long rejectedFrames() {
        return rejectedFrames;
    }

    /** Slides the pending window without allocating another buffer. */
    private void discardFirst() {
        System.arraycopy(pending, 1, pending, 0, --length);
    }

    /**
     * Validates one exact live frame, then reads four signed little-endian int16
     * values in tenths Celsius. Only the exact 28000 sentinel means an open
     * probe; zero and negative readings are valid. Mask bit zero denotes T1.
     *
     * @throws IllegalArgumentException if framing, command or checksum is invalid
     */
    public static Sample decode(byte[] frame) {
        if (frame.length != FRAME_SIZE || frame[0] != 0x55 || frame[1] != (byte) 0xAA
                || frame[2] != 0x01 || frame[3] != 0x0B || !checksumValid(frame)) {
            throw new IllegalArgumentException("Invalid TA612C live frame");
        }
        int[] raw = new int[PROBE_COUNT];
        int validMask = 0;
        for (int i = 0; i < PROBE_COUNT; ++i) {
            int offset = 4 + 2 * i;
            raw[i] = (short) ((frame[offset] & 0xFF) | ((frame[offset + 1] & 0xFF) << 8));
            if (raw[i] != OPEN_PROBE_RAW) validMask |= 1 << i;
        }
        return new Sample(raw, validMask);
    }

    /** Compares the trailing byte with the unsigned sum modulo 256. */
    private static boolean checksumValid(byte[] frame) {
        int sum = 0;
        for (int i = 0; i < FRAME_SIZE - 1; ++i) sum += frame[i] & 0xFF;
        return (sum & 0xFF) == (frame[FRAME_SIZE - 1] & 0xFF);
    }

    /**
     * One decoded group in physical T1..T4 order, with zero-based probe indices.
     * Raw values include open-probe sentinels for diagnostics; point conversion
     * must exclude those sentinels. The decoder owns the unexposed raw array.
     */
    public static final class Sample {
        private final int[] raw;
        private final int validMask;

        /** Takes ownership of the newly decoded array and its presence mask. */
        private Sample(int[] raw, int validMask) {
            this.raw = raw;
            this.validMask = validMask;
        }

        public int validMask() { return validMask; }
        public int raw(int probe) { return raw[probe]; }
        public boolean isValid(int probe) { return (validMask & (1 << probe)) != 0; }
        public boolean allProbesConnected() { return validMask == 0x0F; }

        /**
         * Returns only present probes in physical order, scaled from tenths to
         * DataExplorer thousandths Celsius. All-open samples return no points.
         */
        public int[] presentPoints() {
            int[] points = new int[Integer.bitCount(validMask)];
            int column = 0;
            for (int probe = 0; probe < PROBE_COUNT; ++probe) {
                if (isValid(probe)) points[column++] = raw[probe] * 100;
            }
            return points;
        }

        /**
         * Converts to the legacy four-column point layout. Rejects open probes
         * rather than storing the sentinel as a temperature; sparse acquisition
         * uses {@link #presentPoints()} instead.
         */
        public int[] points() {
            if (!allProbesConnected()) {
                throw new IllegalStateException("TA612C open probe; four-column conversion requires all four probes");
            }
            return Arrays.stream(raw).map(value -> value * 100).toArray();
        }

        /** Formats absent probe names for status messages without altering data. */
        public String openProbes() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < PROBE_COUNT; ++i) if (!isValid(i)) names.add("T" + (i + 1));
            return String.join(", ", names);
        }

        /** Formats connected probe names in physical order for status messages. */
        public String presentProbes() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < PROBE_COUNT; ++i) if (isValid(i)) names.add("T" + (i + 1));
            return String.join(", ", names);
        }
    }
}
