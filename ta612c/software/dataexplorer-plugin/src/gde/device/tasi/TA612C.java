/* SPDX-License-Identifier: GPL-3.0-or-later
 * DataExplorer integration follows the GPL-3.0-or-later CSV2SerialAdapter
 * by Winfried Bruegmann (2008-2026). TA612C protocol follows the local logger.
 */
package gde.device.tasi;

import gde.GDE;
import gde.comm.DeviceCommPort;
import gde.comm.IDeviceCommPort;
import gde.data.Channel;
import gde.data.Channels;
import gde.data.Record;
import gde.data.RecordSet;
import gde.device.DeviceConfiguration;
import gde.device.IDevice;
import gde.exception.DataInconsitsentException;
import gde.ui.DataExplorer;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBException;

/** Standalone TA612C device: live/REC temperatures and native OSD restoration. */
public class TA612C extends DeviceConfiguration implements IDevice {
    private static final Logger LOG = Logger.getLogger(TA612C.class.getName());
    private final DataExplorer application;
    private TA612CSerialPort livePort;
    private TA612CGathererThread gatherer;
    private TA612CRecDownloader recDownloader;

    public TA612C(String properties) throws FileNotFoundException, JAXBException {
        super(properties);
        application = GDE.isWithUi() ? DataExplorer.getInstance() : null;
        // Core port discovery needs a registered backend before the first Start.
        // Construction keeps the physical port closed and sends no commands.
        livePort = new TA612CSerialPort(this, application);
        configureMenu();
    }

    public TA612C(DeviceConfiguration configuration) {
        super(configuration);
        application = GDE.isWithUi() ? DataExplorer.getInstance() : null;
        livePort = new TA612CSerialPort(this, application);
        configureMenu();
    }

    private void configureMenu() {
        if (application != null) {
            configureSerialPortMenu(DeviceCommPort.ICON_SET_START_STOP,
                    "Start TA612C live acquisition (available probes are recorded)", "Stop TA612C acquisition / cancel REC download");
            addRecMenu(application.getMenuBar().getImportMenu());
        }
    }

    void addRecMenu(Menu menu) {
        for (MenuItem item : menu.getItems()) if ("ta612c-rec".equals(item.getData())) return;
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setData("ta612c-rec");
        item.setText("Download TA612C REC memory... / Cancel REC");
        item.addListener(SWT.Selection, event -> downloadRec());
    }

    private synchronized void downloadRec() {
        if (application.getActiveDevice() != this) return;
        if (recDownloader != null) { recDownloader.requestStop(); return; }
        if (gatherer != null) {
            application.openMessageDialog("Stop live acquisition before downloading REC memory.");
            return;
        }
        Long intervalMs = TA612CRecDialog.open(application.getShell());
        // The modal dialog dispatches events: recheck ownership and the selected device afterwards.
        if (intervalMs == null || gatherer != null || recDownloader != null || application.getActiveDevice() != this) return;
        Channel channel = Channels.getInstance().getActiveChannel();
        if (channel == null || channel.getNumber() != 1 || getNumberOfMeasurements(1) != 4) return;
        livePort = new TA612CSerialPort(this, application);
        recDownloader = new TA612CRecDownloader(livePort, (result, failure, cancelled) -> {
            if (failure != null) LOG.log(Level.WARNING, "REC download failed", failure);
            if (GDE.display == null || GDE.display.isDisposed()) return;
            GDE.display.asyncExec(() -> {
                synchronized (TA612C.this) {
                    boolean stopped = cancelled || recDownloader.isStopRequested();
                    recDownloader = null;
                    if (application.getActiveDevice() != TA612C.this) return;
                    application.setPortConnected(false);
                    if (stopped) {
                        application.setStatusMessage("REC download cancelled. Nothing imported.");
                    } else if (failure != null) {
                        application.openMessageDialog("REC download failed: " + failure.getMessage() + ". Nothing imported.");
                    } else {
                        try {
                            List<RecordSet> prepared = TA612CRecImport.prepare(TA612C.this, result, intervalMs, channel.getNextRecordSetNumber());
                            // Publish only after all segments have been validated and prepared.
                            for (RecordSet records : prepared) channel.put(records.getName(), records);
                            if (!prepared.isEmpty()) {
                                RecordSet first = prepared.get(0);
                                channel.setActiveRecordSet(first.getName());
                                application.getMenuToolBar().updateRecordSetSelectCombo();
                                application.updateAllTabs(false);
                                application.updateStatisticsData();
                                application.updateDataTable(first.getName(), false);
                            }
                            String message = "REC: " + result.samples().size() + " sample groups received, " + prepared.size()
                                    + " segments imported. Time inferred; recording date unknown; transfer completeness unverified.";
                            if (prepared.isEmpty()) message += " No valid temperatures returned; this does not prove memory is empty.";
                            application.setStatusMessage(message);
                            application.openMessageDialog(message);
                        } catch (Exception error) {
                            LOG.log(Level.WARNING, "REC import failed", error);
                            application.openMessageDialog("REC import failed: " + error.getMessage());
                        }
                    }
                }
            });
        });
        application.setPortConnected(true);
        application.setStatusMessage("Downloading REC memory. Press Stop or use the REC menu again to cancel.");
        recDownloader.start();
    }

    @Override
    public IDeviceCommPort getCommunicationPort() { return livePort; }

    @Override
    public synchronized void open_closeCommPort() {
        if (application == null) throw new UnsupportedOperationException("Live acquisition requires DataExplorer UI");
        if (recDownloader != null) {
            recDownloader.requestStop();
            application.setStatusMessage("Cancelling REC download...");
            return;
        }
        if (gatherer != null) {
            gatherer.requestStop();
            application.setStatusMessage("Stopping TA612C...");
            return;
        }
        Channel channel = Channels.getInstance().getActiveChannel();
        if (channel == null) return;
        if (channel.getNumber() != 1 || getChannelCount() != 1 || getNumberOfMeasurements(1) != 4 || getTimeStep_ms() >= 0) {
            application.openMessageDialog("TA612C requires one channel, four measurements and a variable time step.");
            return;
        }
        livePort = new TA612CSerialPort(this, application);
        gatherer = new TA612CGathererThread(livePort, new TA612CGathererThread.Listener() {
            private RecordSet records;
            private int probeMask = -1;

            @Override
            public void onSample(TA612CFrameDecoder.Sample sample, double elapsedMs, long firstSampleEpochMs) {
                if (GDE.display == null || GDE.display.isDisposed()) {
                    gatherer.requestStop();
                    return;
                }
                GDE.display.syncExec(() -> {
                    if (gatherer.isStopRequested()) return;
                    if (application.getActiveDevice() != TA612C.this) {
                        gatherer.requestStop();
                        return;
                    }
                    if (sample.validMask() != probeMask) {
                        if (records != null) {
                            makeInActiveDisplayable(records);
                            records.updateVisibleAndDisplayableRecordsForTable();
                            application.updateStatisticsData();
                            application.updateDataTable(records.getName(), false);
                            records = null;
                        }
                        probeMask = sample.validMask();
                        if (probeMask == 0) {
                            application.setStatusMessage("TA612C live acquisition: all four probes are open. Waiting; no temperature row is recorded.");
                            application.updateAllTabs(false);
                            return;
                        }
                        String present = TA612CRecImport.probeNames(probeMask);
                        String name = channel.getNextRecordSetNumber() + ") TA612C live " + present;
                        records = RecordSet.createRecordSet(name, TA612C.this, 1, true, false, true);
                        channel.put(name, records);
                        channel.setActiveRecordSet(name);
                        channel.applyTemplateBasics(name);
                        configureLiveSegment(records, probeMask, firstSampleEpochMs);
                        application.getMenuToolBar().updateRecordSetSelectCombo();
                        if (probeMask == 0x0F) {
                            application.setStatusMessage("TA612C live acquisition: T1-T4 connected.");
                        } else {
                            application.setStatusMessage("TA612C live acquisition: recording " + sample.presentProbes()
                                    + "; open probes " + sample.openProbes() + ". Probe changes start new segments.");
                        }
                    }
                    if (records == null) return; // Consecutive all-open frames carry no temperature data.
                    try {
                        appendLiveSample(records, sample, elapsedMs);
                    } catch (DataInconsitsentException e) {
                        throw new IllegalStateException(e);
                    }
                    updateVisibilityStatus(records, true);
                    records.updateVisibleAndDisplayableRecordsForTable();
                    application.updateAllTabs(false);
                });
            }

            @Override
            public void onStopped(String message, Exception failure) {
                if (failure != null) LOG.log(Level.WARNING, message, failure);
                if (GDE.display != null && !GDE.display.isDisposed()) {
                    GDE.display.asyncExec(() -> {
                        synchronized (TA612C.this) { gatherer = null; }
                        if (application.getActiveDevice() == TA612C.this) {
                            if (records != null) {
                                makeInActiveDisplayable(records);
                                application.updateStatisticsData();
                                application.updateDataTable(records.getName(), false);
                            }
                            application.setPortConnected(false);
                            application.setStatusMessage(message);
                        }
                    });
                }
            }
        });
        application.setPortConnected(true);
        application.setStatusMessage("Waiting for TA612C. Available probes will be recorded; probe changes start new segments.");
        gatherer.start();
    }

    static void configureLiveSegment(RecordSet records, int probeMask, long firstSampleEpochMs) {
        if (probeMask < 1 || probeMask > 0x0F) throw new IllegalArgumentException("A live segment requires at least one present probe");
        records.setTimeStep_ms(-1);
        records.setStartTimeStamp(firstSampleEpochMs);
        records.setRecordSetDescription("TA612C live. Time is based on host receipt, starting at the first decoded frame in this acquisition. "
                + "Present probes " + TA612CRecImport.probeNames(probeMask) + "; other probes are open and have no replacement points. "
                + "A probe-availability change starts a new live record set; all-open frames contain no temperature rows.");
        TA612CRecImport.configureMask(records, probeMask);
    }

    static void appendLiveSample(RecordSet records, TA612CFrameDecoder.Sample sample, double elapsedMs)
            throws DataInconsitsentException {
        String[] stored = records.getNoneCalculationRecordNames();
        if (stored.length != Integer.bitCount(sample.validMask())) {
            throw new IllegalArgumentException("Live sample probe mask does not match its RecordSet segment");
        }
        int column = 0;
        for (int probe = 0; probe < TA612CFrameDecoder.PROBE_COUNT; ++probe) {
            if (sample.isValid(probe) && !stored[column++].equals(TA612CRecImport.probe(records, probe).getName())) {
                throw new IllegalArgumentException("Live sample probe mask does not match its RecordSet segment");
            }
        }
        records.addNoneCalculationRecordsPoints(sample.presentPoints(), elapsedMs);
    }

    @Override
    public int[] convertDataBytes(int[] points, byte[] frame) {
        if (points.length != 4) throw new IllegalArgumentException("TA612C requires four points");
        int[] decoded = TA612CFrameDecoder.decode(frame).points();
        System.arraycopy(decoded, 0, points, 0, 4);
        return points;
    }

    /** OSD buffers are big-endian int32, unlike the device's int16 little-endian frames. */
    @Override
    public void addDataBufferAsRawDataPoints(RecordSet records, byte[] buffer, int count, boolean showProgress)
            throws DataInconsitsentException {
        boolean variable = !records.isTimeStepConstant();
        String[] stored = records.getNoneCalculationRecordNames();
        long expected = (long) count * (stored.length * Integer.BYTES + (variable ? Integer.BYTES : 0));
        if (count < 0 || records.size() != 4 || stored.length < 1 || stored.length > 4
                || expected != buffer.length) {
            throw new DataInconsitsentException("Invalid TA612C OSD buffer length or measurement count");
        }
        ByteBuffer data = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN);
        int timestampBytes = variable ? count * Integer.BYTES : 0;
        // Validate the entire buffer before modifying records, so malformed input is atomic.
        int previousTime = -1;
        for (int i = 0; i < count; ++i) {
            if (variable) {
                int time = data.getInt(i * Integer.BYTES);
                if (time < 0 || time < previousTime) {
                    throw new DataInconsitsentException("Invalid TA612C OSD timestamps");
                }
                previousTime = time;
            }
            for (int probe = 0; probe < stored.length; ++probe) {
                int point = data.getInt(timestampBytes + (i * stored.length + probe) * Integer.BYTES);
                if (point == TA612CFrameDecoder.OPEN_PROBE_RAW * 100) {
                    throw new DataInconsitsentException("TA612C OSD contains an unsupported open-probe point");
                }
            }
        }
        String threadId = Long.toString(Thread.currentThread().threadId());
        int[] points = new int[stored.length];
        for (int i = 0; i < count; ++i) {
            for (int probe = 0; probe < stored.length; ++probe) {
                points[probe] = data.getInt(timestampBytes + (i * stored.length + probe) * Integer.BYTES);
            }
            if (variable) records.addTimeStep_ms(data.getInt(i * Integer.BYTES) / 10.0);
            records.addNoneCalculationRecordsPoints(points);
            if (showProgress && application != null && i % 100 == 0) {
                application.setProgress((int) (100L * i / count), threadId);
            }
        }
        makeInActiveDisplayable(records);
        records.syncScaleOfSyncableRecords();
        if (showProgress && application != null) application.setProgress(100, threadId);
    }

    @Override
    public String[] crossCheckMeasurements(String[] properties, RecordSet records) {
        return TA612CRecImport.restoreMask(properties, records);
    }

    @Override
    public void applyMeasurementSpecialties(String[] properties, RecordSet records) {
        TA612CRecImport.restoreMask(properties, records);
    }

    @Override
    public double translateValue(Record record, double value) {
        return (value - record.getReduction()) * record.getFactor() + record.getOffset();
    }

    @Override
    public double reverseTranslateValue(Record record, double value) {
        return (value - record.getOffset()) / record.getFactor() + record.getReduction();
    }

    @Override
    public String[] prepareDataTableRow(RecordSet records, String[] row, int index) {
        int column = 1;
        for (Record record : records.getVisibleAndDisplayableRecordsForTable()) {
            row[column++] = record.getFormattedTableValue(index);
        }
        return row;
    }

    @Override
    public void updateVisibilityStatus(RecordSet records, boolean checkData) {
        int visible = 0;
        for (int i = 0; i < records.size(); ++i) {
            Record record = TA612CRecImport.probe(records, i);
            // Zero and negative temperatures are valid; do not use hasReasonableData().
            boolean displayable = !TA612CRecImport.isAbsent(record) && Boolean.TRUE.equals(record.isActive()) && (!checkData || record.realSize() > 0);
            record.setDisplayable(displayable);
            if (displayable) ++visible;
        }
        records.setConfiguredDisplayable(visible);
    }

    @Override
    public void makeInActiveDisplayable(RecordSet records) {
        updateVisibilityStatus(records, true);
        records.updateVisibleAndDisplayableRecordsForTable();
    }

    @Override
    public String[] getUsedPropertyKeys() { return new String[] {OFFSET, FACTOR, REDUCTION, SYNC_ORDINAL, TA612CRecImport.PRESENT}; }

    @Override
    public HashMap<String, String> getLovKeyMappings(HashMap<String, String> map) { throw lovUnsupported(); }
    @Override
    public String getConvertedRecordConfigurations(HashMap<String, String> header, HashMap<String, String> map, int channel) { throw lovUnsupported(); }
    @Override
    public int getLovDataByteSize() { return 0; }
    @Override
    public void addConvertedLovDataBufferAsRawDataPoints(RecordSet records, byte[] buffer, int count, boolean progress) { throw lovUnsupported(); }
    private static UnsupportedOperationException lovUnsupported() {
        return new UnsupportedOperationException("TA612C LogView import is not supported");
    }
}
