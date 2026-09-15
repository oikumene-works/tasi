/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.comm.DeviceCommPort;
import gde.device.IDevice;
import gde.exception.TimeOutException;
import gde.ui.DataExplorer;
import java.io.IOException;
import java.util.List;

/** Adapter around DataExplorer's existing serial backend. One worker owns the port. */
public class TA612CSerialPort extends DeviceCommPort implements TA612CGathererThread.Transport, TA612CRecDownloader.Transport {
    private static final String CONNECTION_UNAVAILABLE =
            "USB/serial connection unavailable. Check cable and press Start";
    private final TA612CFrameDecoder decoder = new TA612CFrameDecoder();
    private final int readTimeout;
    private static TA612CSerialPort owner;

    public TA612CSerialPort(IDevice device, DataExplorer application) {
        super(device, application);
        // The backend's timeout applies in multiple phases. Keep each read small.
        readTimeout = Math.max(100, Math.min(700, device.getDeviceConfiguration().getReadTimeOut()));
    }

    public void startLive() throws IOException {
        decoder.reset();
        // DataExplorer.write() also drains the receive buffer. Never call per frame.
        sendCommand(TA612CFrameDecoder.liveCommand());
    }

    public void startRec() throws IOException {
        decoder.reset();
        sendCommand(TA612CRecDecoder.downloadCommand());
    }

    private void sendCommand(byte[] command) throws IOException {
        try {
            write(command);
        } catch (NegativeArraySizeException error) {
            // Core cleanInputStream allocates available() bytes. A disconnect
            // between reads can make jSerialComm return -1 during that cleanup.
            throw new IOException(CONNECTION_UNAVAILABLE, error);
        }
    }

    public void connect() throws Exception {
        synchronized (TA612CSerialPort.class) {
            if (owner != null || isConnected()) throw new IllegalStateException("TA612C live/REC port is already in use");
            owner = this;
        }
        open(); // The worker closes even a partially failed open.
    }

    @Override public void close() {
        synchronized (TA612CSerialPort.class) {
            if (owner != this) return; // A refused second worker must not close the first one's port.
        }
        super.close();
        TA612CSerialClose.await(port);
        // On an uncertain close keep the ownership guard latched until application restart.
        synchronized (TA612CSerialPort.class) { owner = null; }
    }

    public List<TA612CFrameDecoder.Sample> readSamples() throws IOException, TimeOutException {
        return decoder.accept(readRecBytes());
    }

    public byte[] readRecBytes() throws IOException, TimeOutException {
        int available = getAvailableBytes();
        if (available < 0) throw new IOException(CONNECTION_UNAVAILABLE);
        if (available == 0) return new byte[0];
        // Read only bytes already present: a partial protocol frame stays in decoder.
        byte[] chunk = read(new byte[Math.min(available, 256)], readTimeout);
        return chunk;
    }
}
