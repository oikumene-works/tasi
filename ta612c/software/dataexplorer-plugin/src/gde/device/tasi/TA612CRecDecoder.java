/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Strict REC stream parser. Never resynchronize past missing samples into a shorter timeline. */
public final class TA612CRecDecoder {
    private final byte[] pending = new byte[257];
    private int length;
    private int frames;
    private int emptyFrames;
    private boolean failed;

    public static byte[] downloadCommand() {
        return new byte[] {(byte) 0xAA, 0x55, 0x02, 0x03, 0x04};
    }

    public List<Sample> accept(byte[] bytes) throws IOException {
        if (failed) throw new IOException("REC decoder is invalid after a transfer error");
        List<Sample> result = new ArrayList<>();
        for (byte next : bytes) {
            pending[length++] = next;
            if (pending[0] != 0x55 || (length >= 2 && pending[1] != (byte) 0xAA)) fail("Unexpected bytes");
            if (length >= 3 && pending[2] != 0x02) fail("Unexpected response command");
            if (length >= 4) {
                int total = (pending[3] & 0xFF) + 2;
                if (total < 5 || (total - 5) % 8 != 0) fail("Malformed REC payload length");
                if (length == total) {
                    int sum = 0;
                    for (int i = 0; i < total - 1; ++i) sum += pending[i] & 0xFF;
                    if ((sum & 0xFF) != (pending[total - 1] & 0xFF)) fail("REC checksum mismatch");
                    ++frames;
                    if (total == 5) ++emptyFrames;
                    for (int offset = 4; offset < total - 1; offset += 8) {
                        int[] raw = new int[4];
                        for (int probe = 0; probe < 4; ++probe) {
                            int p = offset + 2 * probe;
                            raw[probe] = (short) ((pending[p] & 0xFF) | ((pending[p + 1] & 0xFF) << 8));
                        }
                        result.add(new Sample(raw, frames));
                    }
                    length = 0;
                }
            }
        }
        return result;
    }

    public void endOfInput() throws IOException {
        if (failed || length != 0) fail("Incomplete REC frame at end of transfer");
    }
    public int frames() { return frames; }
    public int emptyFrames() { return emptyFrames; }
    private void fail(String message) throws IOException {
        failed = true;
        throw new IOException(message + "; nothing imported (sample continuity is unknown)");
    }

    public static final class Sample {
        private final int[] raw;
        private final int sourceFrame;
        private Sample(int[] raw, int sourceFrame) { this.raw = raw; this.sourceFrame = sourceFrame; }
        public int raw(int probe) { return raw[probe]; }
        public int sourceFrame() { return sourceFrame; }
        public int validMask() {
            int mask = 0;
            for (int i = 0; i < 4; ++i) if (raw[i] != TA612CFrameDecoder.OPEN_PROBE_RAW) mask |= 1 << i;
            return mask;
        }
        public int point(int probe) {
            if ((validMask() & (1 << probe)) == 0) throw new IllegalStateException("Open probe has no temperature");
            return raw[probe] * 100;
        }
    }
}
