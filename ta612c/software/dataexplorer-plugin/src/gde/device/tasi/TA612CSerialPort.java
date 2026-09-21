/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.comm.DeviceCommPort;
import gde.device.IDevice;
import gde.exception.TimeOutException;
import gde.ui.DataExplorer;
import java.io.IOException;
import java.util.List;

/**
 * Adapts the DataExplorer serial backend to both worker transport interfaces.
 * A class-wide guard permits only one TA612C live/REC owner, even across device
 * instances and regardless of selected port. The guard extends through the
 * core's asynchronous close; individual reads/writes belong to the owning
 * worker. Construction registers a closed backend for port discovery.
 */
public class TA612CSerialPort extends DeviceCommPort implements TA612CGathererThread.Transport, TA612CRecDownloader.Transport {
    private static final String CONNECTION_UNAVAILABLE =
            "USB/serial connection unavailable. Check cable and press Start";
    private final TA612CFrameDecoder decoder = new TA612CFrameDecoder();
    private final int readTimeout;
    private static TA612CSerialPort owner;

    /** Registers the backend and bounds each read timeout without opening a port. */
    public TA612CSerialPort(IDevice device, DataExplorer application) {
        super(device, application);
        // The backend's timeout applies in multiple phases. Keep each read small.
        readTimeout = Math.max(100, Math.min(700, device.getDeviceConfiguration().getReadTimeOut()));
    }

    /**
     * Discards pending decoder bytes and sends the live request. The gatherer
     * also calls this on a bounded silence restart; it is not a per-frame poll.
     */
    public void startLive() throws IOException {
        decoder.reset();
        // DataExplorer.write() also drains the receive buffer. Never call per frame.
        sendCommand(TA612CFrameDecoder.liveCommand());
    }

    /** Sends the read-only REC request; REC framing belongs to the downloader. */
    public void startRec() throws IOException {
        decoder.reset();
        sendCommand(TA612CRecDecoder.downloadCommand());
    }

    /**
     * Uses the core write path and translates its known disconnect-during-drain
     * failure into an I/O error the workers can report and clean up normally.
     */
    private void sendCommand(byte[] command) throws IOException {
        try {
            write(command);
        } catch (NegativeArraySizeException error) {
            // Core cleanInputStream allocates available() bytes. A disconnect
            // between reads can make jSerialComm return -1 during that cleanup.
            throw new IOException(CONNECTION_UNAVAILABLE, error);
        }
    }

    /**
     * Claims ownership before opening the port, so even a partially failed open
     * remains this worker's cleanup responsibility. A rejected contender never
     * acquires ownership and must not close the active owner's transport.
     */
    public void connect() throws Exception {
        synchronized (TA612CSerialPort.class) {
            if (owner != null || isConnected()) throw new IllegalStateException("TA612C live/REC port is already in use");
            owner = this;
        }
        open(); // The worker closes even a partially failed open.
    }

    /**
     * Closes only this owner's connection and releases the guard after confirmed
     * asynchronous completion. A close/wait failure leaves the guard locked;
     * restarting the application is required if closure cannot be confirmed.
     */
    @Override public void close() {
        synchronized (TA612CSerialPort.class) {
            if (owner != this) return; // A refused second worker must not close the first one's port.
        }
        super.close();
        TA612CSerialClose.await(port);
        // On an uncertain close keep the ownership guard latched until application restart.
        synchronized (TA612CSerialPort.class) { owner = null; }
    }

    /** Decodes one available chunk, retaining any incomplete live frame. */
    public List<TA612CFrameDecoder.Sample> readSamples() throws IOException, TimeOutException {
        return decoder.accept(readRecBytes());
    }

    /**
     * Shared chunk reader for live and REC: reads at most 256 already-available
     * bytes. Zero available bytes is ordinary silence; a negative count denotes
     * disconnection. The workers intentionally interpret read timeouts differently.
     */
    public byte[] readRecBytes() throws IOException, TimeOutException {
        int available = getAvailableBytes();
        if (available < 0) throw new IOException(CONNECTION_UNAVAILABLE);
        if (available == 0) return new byte[0];
        // Read only bytes already present: a partial protocol frame stays in decoder.
        byte[] chunk = read(new byte[Math.min(available, 256)], readTimeout);
        return chunk;
    }
}
