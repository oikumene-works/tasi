/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.Analyzer;
import gde.GDE;
import gde.TestAnalyzer;
import gde.comm.DeviceCommPort;
import gde.comm.DeviceJavaSerialCommPortImpl;
import gde.data.Channels;
import gde.data.RecordSet;
import gde.device.DeviceConfiguration;
import gde.exception.DataInconsitsentException;
import gde.exception.TimeOutException;
import gde.tools.ExportServiceBuilder;
import gde.DataAccess;
import gde.io.OsdReaderWriter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

/** No instrument, network, or existing settings. Integration uses an isolated display. */
public final class TA612CTests {
    private static int assertions;
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            protocolTests();
            lifecycleTests();
            TA612CRecTests.protocol();
            System.out.println("PASS: " + assertions + " protocol and lifecycle assertions");
            return;
        }
        Path xml = Path.of(args[0]);
        // The upstream settings loader reads before creating these directories.
        Files.createDirectories(Path.of(System.getProperty("user.home"), ".DataExplorer", "Mapping"));
        SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(Path.of(args[1]).toFile()).newValidator().validate(new StreamSource(xml.toFile()));
        try {
            GDE.display = Display.getDefault();
            GDE.shell = new Shell(GDE.display);
            integrationTests(xml, Path.of(args[2]), Path.of(args[3]));
            System.out.println("PASS: " + assertions + " XML, loader and OSD integration assertions");
        } finally {
            if (GDE.shell != null) GDE.shell.dispose();
            if (GDE.display != null) GDE.display.dispose();
        }
    }

    private static void protocolTests() {
        equal(TA612CFrameDecoder.liveCommand(), HEX.parseHex("aa55010303"), "live command");
        // Independent fixed vector: 25.3, -12.3, 0.0, 100.0 Celsius.
        byte[] golden = HEX.parseHex("55aa010bfd0085ff0000e80377");
        var sample = TA612CFrameDecoder.decode(golden);
        equal(sample.points(), new int[] {25300, -12300, 0, 100000}, "signed/scaled golden temperatures");
        equal(sample.presentPoints(), sample.points(), "present points match four-column points when all probes are connected");
        check(sample.validMask() == 15, "all probes valid");
        equal(TA612CFrameDecoder.decode(frame(-32768, 32767, -1, 1)).points(),
                new int[] {-3276800, 3276700, -100, 100}, "int16 bounds");
        for (int split = 0; split <= golden.length; ++split) {
            TA612CFrameDecoder decoder = new TA612CFrameDecoder();
            List<TA612CFrameDecoder.Sample> samples = new ArrayList<>();
            samples.addAll(decoder.accept(Arrays.copyOfRange(golden, 0, split)));
            samples.addAll(decoder.accept(Arrays.copyOfRange(golden, split, golden.length)));
            check(samples.size() == 1, "split " + split);
            equal(samples.get(0).points(), sample.points(), "split values " + split);
        }
        TA612CFrameDecoder decoder = new TA612CFrameDecoder();
        check(decoder.accept(new byte[100_000]).isEmpty(), "noise discarded in bounded memory");
        check(decoder.accept(new byte[] {0x55}).isEmpty(), "partial header retained");
        check(decoder.accept(golden).size() == 1, "overlapping header resynchronizes");
        byte[] bad = golden.clone();
        bad[12] ^= 1;
        check(decoder.accept(concat(bad, golden, golden)).size() == 2, "bad checksum followed by coalesced frames");
        check(decoder.rejectedFrames() == 1, "checksum rejection counted");
        for (int index = 0; index < golden.length; ++index) {
            byte[] changed = golden.clone();
            changed[index] ^= 0x40;
            expect(IllegalArgumentException.class, () -> TA612CFrameDecoder.decode(changed), "corrupt byte " + index);
            check(new TA612CFrameDecoder().accept(concat(changed, golden)).size() == 1, "recovery " + index);
        }
        expect(IllegalArgumentException.class, () -> TA612CFrameDecoder.decode(new byte[12]), "short frame");
        expect(IllegalArgumentException.class, () -> TA612CFrameDecoder.decode(new byte[14]), "long frame");
        for (int probe = 0; probe < 4; ++probe) {
            int[] raw = {10, 20, 30, 40};
            raw[probe] = 28000;
            var missing = TA612CFrameDecoder.decode(frame(raw));
            check(missing.validMask() == (15 ^ (1 << probe)), "missing probe mask");
            check(missing.openProbes().equals("T" + (probe + 1)), "missing probe name");
            check(!missing.presentProbes().contains("T" + (probe + 1)), "missing probe omitted from present names");
            int[] expected = Arrays.stream(raw).filter(value -> value != 28000).map(value -> value * 100).toArray();
            equal(missing.presentPoints(), expected, "only present probe values returned");
            expect(IllegalStateException.class, missing::points, "missing probe cannot become temperature");
        }
        var sparse = TA612CFrameDecoder.decode(frame(28000, -10, 28000, 0));
        check(sparse.validMask() == 0x0A && sparse.presentProbes().equals("T2, T4"), "multiple present probes retain wire order");
        equal(sparse.presentPoints(), new int[] {-1000, 0}, "sparse points contain no sentinel replacement");
        var allOpen = TA612CFrameDecoder.decode(frame(28000, 28000, 28000, 28000));
        check(allOpen.validMask() == 0 && allOpen.presentProbes().isEmpty(), "all-open sample has no present probes");
        equal(allOpen.presentPoints(), new int[0], "all-open sample has no temperature points");
        check(TA612CFrameDecoder.decode(frame(27999, 28001, -28000, 0)).allProbesConnected(), "sentinel is exact");
        int[] mutable = sample.points();
        mutable[0] = 5;
        check(sample.points()[0] == 25300, "sample values cannot be changed by caller");
        decoder.accept(Arrays.copyOf(golden, 6));
        decoder.reset();
        check(decoder.accept(golden).size() == 1, "new stream clears partial frame");
    }

    private static void lifecycleTests() throws Exception {
        FakeTransport port = new FakeTransport();
        port.batches.add(List.of(TA612CFrameDecoder.decode(frame(1, 2, 3, 4)),
                TA612CFrameDecoder.decode(frame(1, 28000, 3, 4)),
                TA612CFrameDecoder.decode(frame(28000, 28000, 28000, 28000)),
                TA612CFrameDecoder.decode(frame(5, 28000, 7, 28000))));
        Capture capture = new Capture();
        capture.stopAfterSamples = 4;
        run(port, capture);
        check(capture.samples == 4 && port.closes == 1, "missing and all-open probe frames do not stop live acquisition");
        check(capture.masks.equals(List.of(15, 13, 0, 5)), "listener receives every probe availability transition");
        check(capture.failure == null, "probe availability changes are not live failures");
        check(capture.times.get(0) == 0.0, "first timestamp is zero");
        check(capture.times.get(3) >= capture.times.get(0), "host-receive live time continues across probe changes");

        port = new FakeTransport(); capture = new Capture();
        run(port, capture);
        check(port.starts == 4 && port.closes == 1, "initial command plus three bounded restarts");
        check(capture.failure instanceof IOException && capture.samples == 0, "silence exits with error");

        port = new FakeTransport(); port.timeout = true; capture = new Capture();
        run(port, capture);
        check(port.starts == 4 && port.closes == 1, "read timeouts also exhaust retry budget");

        port = new FakeTransport(); port.openFailure = true; capture = new Capture();
        run(port, capture);
        check(port.starts == 0 && port.closes == 1 && capture.failure != null, "failed open cleanup");

        port = new FakeTransport(); port.writeFailure = true; capture = new Capture();
        run(port, capture);
        check(port.closes == 1 && capture.failure != null, "failed command cleanup");

        port = new FakeTransport(); port.readFailure = true; capture = new Capture();
        run(port, capture);
        check(port.closes == 1 && capture.failure != null, "disconnect cleanup");

        port = new FakeTransport(); capture = new Capture();
        port.batches.add(List.of(TA612CFrameDecoder.decode(frame(100, 200, 300, 400))));
        IOException disconnect = new IOException("USB/serial connection unavailable");
        port.readFailureAfterData = disconnect;
        run(port, capture);
        check(capture.samples == 1 && port.closes == 1, "disconnect retains preceding sample and closes once");
        check(capture.failure == disconnect, "original disconnect exception retained for diagnostics");

        port = new FakeTransport(); capture = new Capture();
        TA612CGathererThread worker = new TA612CGathererThread(port, capture);
        worker.start();
        check(port.connected.await(2, TimeUnit.SECONDS), "worker connected");
        worker.requestStop(); worker.join(2000);
        check(!worker.isAlive() && port.closes == 1 && capture.failure == null, "user stop is bounded");

        port = new FakeTransport(); capture = new Capture();
        worker = new TA612CGathererThread(port, capture);
        worker.requestStop(); worker.start(); worker.join(2000);
        check(port.opens == 0 && port.closes == 1, "stop before startup avoids connecting");
    }

    private static void integrationTests(Path xml, Path output, Path jar) throws Exception {
        check(!GDE.isWithUi(), "integration tests have no UI");
        // Fresh-process contract: the core returns an empty port list until a
        // serial backend is registered. Verify both loader constructors without
        // enumerating or opening any physical port.
        var backend = DeviceCommPort.class.getDeclaredField("staticPort");
        backend.setAccessible(true);
        backend.set(null, null);
        TA612C device = new TA612C(xml.toString());
        check(backend.get(null) instanceof DeviceJavaSerialCommPortImpl,
                "XML constructor enables core port discovery before Start");
        check(device.getCommunicationPort() != null && !device.getCommunicationPort().isConnected(),
                "XML constructor prepares a closed transport");
        backend.set(null, null);
        TA612C copied = new TA612C(new DeviceConfiguration(xml.toString()));
        check(backend.get(null) instanceof DeviceJavaSerialCommPortImpl,
                "configuration constructor enables core port discovery before Start");
        check(copied.getCommunicationPort() != null && !copied.getCommunicationPort().isConnected(),
                "configuration constructor prepares a closed transport");
        serialDisconnectTests(device);
        TestAnalyzer analyzer = (TestAnalyzer) Analyzer.getInstance();
        analyzer.joinDeviceConfigurationsThread();
        analyzer.setActiveDevice(device);
        Channels channels = Channels.getInstance();
        analyzer.setChannels(channels);
        channels.setupChannels(analyzer);
        check(device.getChannelCount() == 1 && device.getNumberOfMeasurements(1) == 4, "four measurements in one channel");
        check(device.getTimeStep_ms() < 0, "variable timestamps");
        check(device.getDialog() == null, "no custom dialog");
        check(new DeviceConfiguration(xml.toString()).defineInstanceOfDevice() instanceof TA612C, "real device loader constructor");
        try (JarFile packaged = new JarFile(jar.toFile())) {
            check("TA612C:TASI:SERIAL_IO".equals(packaged.getManifest().getMainAttributes().getValue("Export-Service")), "service manifest");
            check(packaged.getEntry("resource/TA612C.xml") != null, "XML packaged");
        }
        var service = new ExportServiceBuilder(DataAccess.getInstance()).getService(xml.getParent().getParent().getParent().getParent(),
                xml.getParent().getParent().getParent().getFileName().toString(), "TA612C.xml");
        check(service != null && service.toString().equals("TA612C:TASI:SERIAL_IO"), "manifest matches core export builder");

        RecordSet records = RecordSet.createRecordSet("1) OSD restoration", analyzer, 1, true, true, false);
        // Fixed OSD payload: two int32 timestamps in 0.1 ms, then rows of four int32 values.
        byte[] payload = HEX.parseHex("0000000000003039000062d4ffffcff400000000000186a0ffffff9c00000064000000000002e63c");
        Files.write(output.resolve("osd-variable-payload.bin"), payload);
        device.addDataBufferAsRawDataPoints(records, Files.readAllBytes(output.resolve("osd-variable-payload.bin")), 2, false);
        check(records.getRecordDataSize(true) == 2, "OSD restored rows");
        equal(row(records, 0), new int[] {25300, -12300, 0, 100000}, "OSD golden first row");
        equal(row(records, 1), new int[] {-100, 100, 0, 190012}, "OSD second row retains arbitrary int32 precision");
        near(records.getTime_ms(1), 1234.5, "OSD time unit");
        near(device.translateValue(records.get(0), records.get(0).realGet(0) / 1000.0), 25.3, "display scale");
        check(records.get(2).isDisplayable(), "constant zero Celsius remains displayable");
        device.makeInActiveDisplayable(records);
        String[] table = device.prepareDataTableRow(records, new String[5], 0);
        check(table[1] != null && table[4] != null, "table values populated");
        records.get(0).setFactor(1.1); records.get(0).setOffset(-0.5); records.get(0).setReduction(0.25);
        near(device.reverseTranslateValue(records.get(0), device.translateValue(records.get(0), 25.3)), 25.3, "calibration inverse");

        RecordSet untouched = RecordSet.createRecordSet("2) malformed", analyzer, 1, true, true, false);
        expect(DataInconsitsentException.class, () -> device.addDataBufferAsRawDataPoints(untouched, Arrays.copyOf(payload, payload.length - 1), 2, false), "OSD truncation");
        byte[] invalid = payload.clone(); ByteBuffer.wrap(invalid).putInt(4, -1);
        expect(DataInconsitsentException.class, () -> device.addDataBufferAsRawDataPoints(untouched, invalid, 2, false), "OSD invalid timestamp");
        byte[] sentinel = payload.clone(); ByteBuffer.wrap(sentinel).putInt(24, 2800000);
        expect(DataInconsitsentException.class, () -> device.addDataBufferAsRawDataPoints(untouched, sentinel, 2, false), "OSD sentinel rejected");
        check(untouched.getRecordDataSize(true) == 0, "OSD rejection does not append earlier rows");
        device.addDataBufferAsRawDataPoints(untouched, new byte[0], 0, false);
        check(untouched.getRecordDataSize(true) == 0, "empty OSD supported");

        device.setTimeStep_ms(1000);
        RecordSet fixed = RecordSet.createRecordSet("3) fixed", analyzer, 1, true, true, false);
        device.addDataBufferAsRawDataPoints(fixed, Arrays.copyOfRange(payload, 8, payload.length), 2, false);
        equal(row(fixed, 1), row(records, 1), "fixed-step OSD supported");
        near(fixed.getTime_ms(1), 1000, "fixed-step time");
        device.setTimeStep_ms(-1);

        // Exercise the real core writer and reader, including ZIP, metadata and byte order.
        channels.get(1).put(records.getName(), records);
        channels.get(1).setActiveRecordSet(records.getName());
        records.setStartTimeStamp(1700000000000L);
        Path osd = output.resolve("ta612c-roundtrip.osd");
        OsdReaderWriter.write(osd.toString(), channels.get(1), GDE.DATA_EXPLORER_FILE_VERSION_INT);
        channels.get(1).clear();
        RecordSet restored = OsdReaderWriter.read(osd.toString());
        check(restored != null && restored.getRecordDataSize(true) == 2, "real OSD file round trip");
        equal(row(restored, 0), row(records, 0), "OSD writer/reader first row");
        equal(row(restored, 1), row(records, 1), "OSD writer/reader second row");
        near(restored.getTime_ms(1), records.getTime_ms(1), "OSD writer/reader timestamp");
        check(restored.getStartTimeStamp() == 1700000000000L, "OSD absolute start time");
        near(restored.get(0).getFactor(), 1.1, "OSD calibration persisted");

        RecordSet sparseLive = RecordSet.createRecordSet("4) live T2+T4", device, 1, true, false, true);
        TA612C.configureLiveSegment(sparseLive, 0x0A, 1700000001000L);
        var sparseLiveSample = TA612CFrameDecoder.decode(frame(28000, -10, 28000, 0));
        TA612C.appendLiveSample(sparseLive, sparseLiveSample, 250.0);
        TA612C.appendLiveSample(sparseLive, TA612CFrameDecoder.decode(frame(28000, 20, 28000, 30)), 500.0);
        device.makeInActiveDisplayable(sparseLive);
        check(sparseLive.getRecordDataSize(true) == 2, "sparse live rows stored");
        check(TA612CRecImport.probe(sparseLive, 0).realSize() == 0 && TA612CRecImport.probe(sparseLive, 2).realSize() == 0,
                "open live probes remain empty");
        check(sparseLive.get(0) == TA612CRecImport.probe(sparseLive, 1)
                        && sparseLive.get(0).realSize() == sparseLive.getRecordDataSize(true),
                "first present live probe anchors UI row and graph indices");
        sparseLive.syncScaleOfSyncableRecords();
        check(sparseLive.get(0).isScaleVisible()
                        && TA612CRecImport.probe(sparseLive, 3).isScaleVisible()
                        && TA612CRecImport.probe(sparseLive, 1).getSyncMasterRecordOrdinal() == -1
                        && TA612CRecImport.probe(sparseLive, 3).getSyncMasterRecordOrdinal() == -1,
                "present live probes use visible independent graph scales when T1 is absent");
        check(TA612CRecImport.probe(sparseLive, 1).realGet(0) == -1000 && TA612CRecImport.probe(sparseLive, 3).realGet(0) == 0,
                "present live probes retain signed and zero values");
        check(TA612CRecImport.probe(sparseLive, 1).realGet(1) == 2000 && TA612CRecImport.probe(sparseLive, 3).realGet(1) == 3000,
                "later sparse live values are stored at a new index");
        check(!TA612CRecImport.probe(sparseLive, 0).isDisplayable() && !TA612CRecImport.probe(sparseLive, 2).isDisplayable(),
                "open live probes are hidden");
        near(sparseLive.getTime_ms(0), 250.0, "sparse live host timestamp retained");
        String[] firstSparseRow = sparseLive.getDataTableRow(0, false);
        String[] secondSparseRow = sparseLive.getDataTableRow(1, false);
        check(!firstSparseRow[0].equals(secondSparseRow[0]), "sparse table row time advances");
        check(!firstSparseRow[1].equals(secondSparseRow[1]) && !firstSparseRow[2].equals(secondSparseRow[2]),
                "sparse table row temperatures advance");
        RecordSet fullLive = RecordSet.createRecordSet("5) live T1-T4", device, 1, true, false, true);
        TA612C.configureLiveSegment(fullLive, 0x0F, 1700000002000L);
        check(TA612CRecImport.probe(fullLive, 0).getSyncMasterRecordOrdinal() == -1
                        && TA612CRecImport.probe(fullLive, 1).getSyncMasterRecordOrdinal() == 0
                        && TA612CRecImport.probe(fullLive, 2).getSyncMasterRecordOrdinal() == 0
                        && TA612CRecImport.probe(fullLive, 3).getSyncMasterRecordOrdinal() == 0,
                "all-probe live segment preserves normal XML scale synchronization");
        expect(IllegalArgumentException.class,
                () -> TA612C.appendLiveSample(sparseLive, TA612CFrameDecoder.decode(frame(-10, 28000, 28000, 0)), 500.0),
                "live sample cannot cross a probe-mask segment");
        TA612CRecTests.integration(device, output, xml.getParent().getParent().getParent().resolve("test/fixtures"), jar);
    }

    private static void serialDisconnectTests(TA612C device) throws Exception {
        TA612CSerialPort adapter = new TA612CSerialPort(device, null);
        var portField = DeviceCommPort.class.getDeclaredField("port");
        portField.setAccessible(true);
        var backend = (DeviceJavaSerialCommPortImpl) portField.get(adapter);
        var inputField = DeviceJavaSerialCommPortImpl.class.getDeclaredField("inputStream");
        var outputField = DeviceJavaSerialCommPortImpl.class.getDeclaredField("outputStream");
        inputField.setAccessible(true);
        outputField.setAccessible(true);
        // Exercise real core delegation with an in-memory stream that reproduces
        // jSerialComm's negative bytesAvailable() result after a disconnect.
        inputField.set(backend, new InputStream() {
            @Override public int available() { return -1; }
            @Override public int read() { return -1; }
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        outputField.set(backend, output);
        expect(IOException.class, adapter::readSamples, "negative available count is a connection error, not silence");
        try {
            adapter.startLive();
            throw new AssertionError("disconnected command must fail");
        } catch (IOException error) {
            check(error.getCause() instanceof NegativeArraySizeException,
                    "core buffer-cleanup disconnect failure preserved as cause");
            check(error.getMessage().contains("USB/serial") && error.getMessage().contains("Start"),
                    "disconnect report identifies connection and recovery action");
        }
        expect(IOException.class, adapter::readRecBytes, "REC rejects negative available count");
        expect(IOException.class, adapter::startRec, "REC rejects disconnect during command cleanup");
        check(output.size() == 0 && !adapter.isConnected(), "disconnect test sends no bytes and opens no port");
        inputField.set(backend, new java.io.ByteArrayInputStream(new byte[0]));
        adapter.startRec();
        equal(output.toByteArray(), HEX.parseHex("aa55020304"), "real adapter writes only the read-only REC command");
    }

    private static int[] row(RecordSet records, int index) {
        return new int[] {records.get(0).realGet(index), records.get(1).realGet(index), records.get(2).realGet(index), records.get(3).realGet(index)};
    }
    private static void run(FakeTransport port, Capture capture) throws InterruptedException {
        TA612CGathererThread worker = new TA612CGathererThread(port, capture, 20, 3);
        capture.worker = worker;
        worker.start(); worker.join(2000);
        if (worker.isAlive()) { worker.requestStop(); worker.join(1000); throw new AssertionError("worker did not finish"); }
    }
    private static final class FakeTransport implements TA612CGathererThread.Transport {
        final List<List<TA612CFrameDecoder.Sample>> batches = new ArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);
        int opens, starts, closes;
        boolean timeout, openFailure, writeFailure, readFailure;
        IOException readFailureAfterData;
        public void connect() throws IOException { ++opens; connected.countDown(); if (openFailure) throw new IOException("open failed"); }
        public void startLive() throws IOException { ++starts; if (writeFailure) throw new IOException("write failed"); }
        public List<TA612CFrameDecoder.Sample> readSamples() throws IOException, TimeOutException {
            if (readFailure) throw new IOException("disconnected");
            if (batches.isEmpty() && readFailureAfterData != null) throw readFailureAfterData;
            if (timeout) throw new TimeOutException("read timeout");
            return batches.isEmpty() ? List.of() : batches.remove(0);
        }
        public void close() { ++closes; }
    }
    private static final class Capture implements TA612CGathererThread.Listener {
        int samples;
        int stopAfterSamples;
        TA612CGathererThread worker;
        final List<Double> times = new ArrayList<>();
        final List<Integer> masks = new ArrayList<>();
        String message;
        Exception failure;
        public void onSample(TA612CFrameDecoder.Sample sample, double elapsedMs, long epochMs) {
            ++samples; times.add(elapsedMs); masks.add(sample.validMask());
            if (stopAfterSamples > 0 && samples == stopAfterSamples) worker.requestStop();
        }
        public void onStopped(String message, Exception failure) { this.message = message; this.failure = failure; }
    }
    private static byte[] frame(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x55).put((byte) 0xAA).put((byte) 1).put((byte) 11);
        for (int value : values) buffer.putShort((short) value);
        int sum = 0;
        for (int i = 0; i < 12; ++i) sum += buffer.array()[i] & 0xFF;
        buffer.put((byte) sum);
        return buffer.array();
    }
    private static byte[] concat(byte[]... arrays) {
        int length = Arrays.stream(arrays).mapToInt(a -> a.length).sum();
        ByteBuffer buffer = ByteBuffer.allocate(length);
        for (byte[] array : arrays) buffer.put(array);
        return buffer.array();
    }
    private static void equal(byte[] actual, byte[] expected, String message) { check(Arrays.equals(actual, expected), message); }
    private static void equal(int[] actual, int[] expected, String message) { check(Arrays.equals(actual, expected), message + ": " + Arrays.toString(actual)); }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual - expected) < 0.00001, message + ": " + actual); }
    private static void check(boolean condition, String message) { ++assertions; if (!condition) throw new AssertionError(message); }
    private interface Action { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Action action, String message) {
        ++assertions;
        try { action.run(); } catch (Exception error) {
            if (type.isInstance(error)) return;
            throw new AssertionError(message, error);
        }
        throw new AssertionError(message + " did not fail");
    }
}
