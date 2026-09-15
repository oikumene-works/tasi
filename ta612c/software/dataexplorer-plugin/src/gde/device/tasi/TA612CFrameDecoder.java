/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure, bounded decoder for the TA612C live byte stream. No serial or UI access. */
public final class TA612CFrameDecoder {
    public static final int FRAME_SIZE = 13;
    public static final int PROBE_COUNT = 4;
    public static final int OPEN_PROBE_RAW = 28000;
    private final byte[] pending = new byte[FRAME_SIZE];
    private int length;
    private long rejectedFrames;

    public static byte[] liveCommand() {
        return new byte[] {(byte) 0xAA, 0x55, 0x01, 0x03, 0x03};
    }

    /** Each input byte is consumed once; a partial header/frame survives across calls. */
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

    public void reset() {
        length = 0;
    }

    public long rejectedFrames() {
        return rejectedFrames;
    }

    private void discardFirst() {
        System.arraycopy(pending, 1, pending, 0, --length);
    }

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

    private static boolean checksumValid(byte[] frame) {
        int sum = 0;
        for (int i = 0; i < FRAME_SIZE - 1; ++i) sum += frame[i] & 0xFF;
        return (sum & 0xFF) == (frame[FRAME_SIZE - 1] & 0xFF);
    }

    public static final class Sample {
        private final int[] raw;
        private final int validMask;

        private Sample(int[] raw, int validMask) {
            this.raw = raw;
            this.validMask = validMask;
        }

        public int validMask() { return validMask; }
        public int raw(int probe) { return raw[probe]; }
        public boolean isValid(int probe) { return (validMask & (1 << probe)) != 0; }
        public boolean allProbesConnected() { return validMask == 0x0F; }

        /** Points for the probes present in this sample, in T1..T4 order. */
        public int[] presentPoints() {
            int[] points = new int[Integer.bitCount(validMask)];
            int column = 0;
            for (int probe = 0; probe < PROBE_COUNT; ++probe) {
                if (isValid(probe)) points[column++] = raw[probe] * 100;
            }
            return points;
        }

        /** DataExplorer uses thousandths; the meter uses tenths of a degree. */
        public int[] points() {
            if (!allProbesConnected()) {
                throw new IllegalStateException("TA612C open probe; four-column conversion requires all four probes");
            }
            return Arrays.stream(raw).map(value -> value * 100).toArray();
        }

        public String openProbes() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < PROBE_COUNT; ++i) if (!isValid(i)) names.add("T" + (i + 1));
            return String.join(", ", names);
        }

        public String presentProbes() {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < PROBE_COUNT; ++i) if (isValid(i)) names.add("T" + (i + 1));
            return String.join(", ", names);
        }
    }
}
