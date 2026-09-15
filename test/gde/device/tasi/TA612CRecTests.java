/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.GDE;
import gde.data.Channels;
import gde.data.Record;
import gde.data.RecordSet;
import gde.exception.TimeOutException;
import gde.io.OsdReaderWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
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
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Button;

final class TA612CRecTests {
    private static int assertions;
    private static final byte[] GOLDEN = HexFormat.of().parseHex("55aa020bfd0085ff0000e80378");
    private static final byte[] EMPTY = HexFormat.of().parseHex("55aa020304");

    static void protocol() throws Exception {
        check(Arrays.equals(TA612CRecDecoder.downloadCommand(), HexFormat.of().parseHex("aa55020304")), "read-only REC command");
        for (int split = 0; split <= GOLDEN.length; ++split) {
            TA612CRecDecoder decoder = new TA612CRecDecoder();
            List<TA612CRecDecoder.Sample> samples = new ArrayList<>();
            samples.addAll(decoder.accept(Arrays.copyOfRange(GOLDEN, 0, split)));
            samples.addAll(decoder.accept(Arrays.copyOfRange(GOLDEN, split, GOLDEN.length)));
            decoder.endOfInput();
            check(samples.size() == 1 && samples.get(0).point(0) == 25300 && samples.get(0).point(1) == -12300
                    && samples.get(0).point(2) == 0 && samples.get(0).point(3) == 100000, "REC signed golden split " + split);
        }
        for (int i = 0; i < GOLDEN.length; ++i) {
            byte[] corrupt = GOLDEN.clone(); corrupt[i] ^= 0x40;
            TA612CRecDecoder decoder = new TA612CRecDecoder();
            expect(IOException.class, () -> { decoder.accept(join(corrupt, GOLDEN)); decoder.endOfInput(); }, "corruption is fatal " + i);
            expect(IOException.class, () -> decoder.accept(GOLDEN), "failed decoder cannot resume " + i);
        }
        for (int n = 1; n < GOLDEN.length; ++n) {
            TA612CRecDecoder decoder = new TA612CRecDecoder(); decoder.accept(Arrays.copyOf(GOLDEN, n));
            expect(IOException.class, decoder::endOfInput, "truncated tail " + n);
        }
        expect(IOException.class, () -> new TA612CRecDecoder().accept(new byte[100000]), "noise cannot silently skip rows");
        byte[] live = GOLDEN.clone(); live[2] = 1; live[12] = 0x77;
        expect(IOException.class, () -> new TA612CRecDecoder().accept(join(GOLDEN, live)), "mixed live and REC rejected");
        expect(IOException.class, () -> new TA612CRecDecoder().accept(new byte[] {0x55, (byte)0xaa, 2, (byte)255}), "non-multiple length rejected");
        TA612CRecDecoder decoder = new TA612CRecDecoder();
        var samples = decoder.accept(join(EMPTY, GOLDEN, EMPTY, GOLDEN));
        check(samples.size() == 2 && decoder.frames() == 4 && decoder.emptyFrames() == 2 && samples.get(1).sourceFrame() == 4,
                "empty frames are counted, not end markers");
        int[][] large = new int[31][4];
        for (int i = 0; i < large.length; ++i) large[i] = new int[] {-32768, 32767, -1, i};
        check(new TA612CRecDecoder().accept(frame(large)).size() == 31, "maximum aligned variable payload");
        for (int mask = 0; mask < 16; ++mask) {
            int[] raw = {28000,28000,28000,28000};
            for (int p = 0; p < 4; ++p) if ((mask & (1 << p)) != 0) raw[p] = p - 2;
            var sample = new TA612CRecDecoder().accept(frame(new int[][] {raw})).get(0);
            check(sample.validMask() == mask, "probe mask " + mask);
            for (int p = 0; p < 4; ++p) if ((mask & (1 << p)) == 0) {
                final int probe = p;
                expect(IllegalStateException.class, () -> sample.point(probe), "absent probe cannot yield point");
            }
        }
        for (String text : new String[] {"", "NaN", "Infinity", "0", "-1", "0.0001", "86400.001", "1.2345"})
            expect(IllegalArgumentException.class, () -> TA612CRecImport.parseIntervalMs(text), "invalid interval " + text);
        check(TA612CRecImport.parseIntervalMs(" 2,5 ") == 2500 && TA612CRecImport.parseIntervalMs("0.001") == 1, "explicit interval parsing");
        lifecycle();
        System.out.println("PASS: " + assertions + " REC protocol/lifecycle assertions");
    }

    private static void lifecycle() throws Exception {
        Fake port = new Fake(GOLDEN, EMPTY); Capture capture = run(port);
        check(capture.result.samples().size() == 1 && capture.result.emptyFrames() == 1 && port.starts == 1 && port.closes == 1,
                "one command, provisional result, close once");
        check(capture.result.completionDescription().contains("unverified"), "silence never certifies completeness");
        capture = run(new Fake(EMPTY));
        check(capture.result.samples().isEmpty(), "empty frame returns no temperatures");
        capture = run(new Fake());
        check(capture.failure instanceof IOException && capture.result == null, "no response is not empty memory");
        for (int failureMode = 1; failureMode <= 5; ++failureMode) {
            port = new Fake(GOLDEN); port.failureMode = failureMode; capture = run(port);
            check(capture.failure != null && capture.result == null && port.closes == 1, "failure atomicity and cleanup " + failureMode);
        }
        byte[] bad = GOLDEN.clone(); bad[12] ^= 1;
        for (byte[] tail : new byte[][] {bad, Arrays.copyOf(GOLDEN, 7), new byte[] {0x55}, EMPTY, GOLDEN}) {
            capture = run(new Fake(GOLDEN, tail));
            if (tail == EMPTY || tail == GOLDEN) check(capture.result != null, "valid coalesced download");
            else check(capture.failure != null && capture.result == null, "no partial import on corrupt/truncated tail");
        }
        port = new Fake(GOLDEN); port.endless = true;
        capture = run(port, 40, 30, 70, 1000);
        check(capture.failure != null && capture.result == null, "bounded endless transfer");
        capture = run(new Fake(GOLDEN, GOLDEN), 40, 30, 1000, 1);
        check(capture.failure != null && capture.result == null, "sample cap rejects entire transfer");
        // Byte fragments arrive every 10 ms, total frame time exceeds the 30 ms quiet period.
        byte[][] fragments = new byte[GOLDEN.length][];
        for (int i = 0; i < GOLDEN.length; ++i) fragments[i] = new byte[] {GOLDEN[i]};
        capture = run(new Fake(fragments));
        check(capture.result.samples().size() == 1, "idle timer tracks bytes, not complete frames");
        port = new Fake(GOLDEN); port.endless = true; capture = new Capture();
        TA612CRecDownloader worker = new TA612CRecDownloader(port, capture);
        worker.start(); check(port.connected.await(2, TimeUnit.SECONDS), "REC connected for cancellation");
        worker.requestStop(); worker.join(2000);
        check(!worker.isAlive() && capture.cancelled && capture.result == null && port.closes == 1, "cancel discards all provisional data");
        port = new Fake(); capture = new Capture(); worker = new TA612CRecDownloader(port, capture);
        worker.requestStop(); worker.start(); worker.join(2000);
        check(port.opens == 0 && port.starts == 0 && port.closes == 1 && capture.cancelled, "cancel before open");
    }

    static void integration(TA612C device, Path output, Path fixtures, Path jar) throws Exception {
        int startAssertions = assertions;
        // All 15 nonempty masks, including T1 absent, and all-absent samples between segments.
        List<int[]> rows = new ArrayList<>();
        rows.add(new int[] {28000,28000,28000,28000});
        for (int mask = 1; mask < 16; ++mask) for (int n = 0; n < 2; ++n) {
            int[] raw = {28000,28000,28000,28000};
            for (int p = 0; p < 4; ++p) if ((mask & (1 << p)) != 0) raw[p] = n == 0 ? -10 * p : 200 + p;
            rows.add(raw);
        }
        rows.add(new int[] {28000,28000,28000,28000});
        rows.add(new int[] {1,2,3,4});
        var result = decoded(frame(rows.subList(0,31).toArray(int[][]::new)), frame(rows.subList(31,rows.size()).toArray(int[][]::new)));
        var sets = TA612CRecImport.prepare(device, result, 2500, 1);
        check(sets.size() == 16, "mask transitions create segments; all-absent rows omitted");
        verifyAndRoundTrip(device, result, sets, 2500, output.resolve("rec-all-masks.osd"));
        check(sets.get(0).getRecordSetDescription().contains("0, 31"), "absent original sample ranges retained");
        final var overflowResult = result;
        expect(IllegalArgumentException.class, () -> TA612CRecImport.prepare(device, overflowResult, 86400000, 1), "OSD time overflow rejected before publishing");
        check(device.getTimeStep_ms() < 0, "REC interval does not change live configuration");
        check(TA612CRecImport.prepare(device, decoded(EMPTY), 5000, 1).isEmpty(), "empty response creates no record set");
        check(TA612CRecImport.prepare(device, decoded(frame(new int[][] {{28000,28000,28000,28000}})), 5000, 1).isEmpty(), "all-absent log creates no fake data");

        List<Path> csvFiles;
        try (var paths = Files.list(fixtures)) { csvFiles = paths.filter(p -> p.toString().endsWith(".csv")).sorted().toList(); }
        for (Path csv : csvFiles) {
            List<String> lines = Files.readAllLines(csv);
            List<int[]> group = new ArrayList<>();
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            int previousFrame = -1;
            for (int i = 1; i < lines.size(); ++i) {
                String[] fields = lines.get(i).split(",");
                check(Integer.parseInt(fields[0]) == i - 1, "fixture sample index");
                int sourceFrame = Integer.parseInt(fields[6]);
                if (previousFrame != -1 && previousFrame != sourceFrame) { stream.write(frame(group.toArray(int[][]::new))); group.clear(); }
                int[] raw = new int[4];
                for (int p = 0; p < 4; ++p) raw[p] = new BigDecimal(fields[p + 2]).movePointRight(1).intValueExact();
                group.add(raw); previousFrame = sourceFrame;
            }
            stream.write(frame(group.toArray(int[][]::new)));
            // Synthetic framing reconstructed from decoded CSV values; NOT captured wire bytes.
            result = decoded(stream.toByteArray());
            check(result.samples().size() == lines.size() - 1, "CSV sample count");
            sets = TA612CRecImport.prepare(device, result, 5000, 1); // Explicit test parameter, not evidence of device interval.
            verifyAndRoundTrip(device, result, sets, 5000, output.resolve(csv.getFileName() + ".osd"));
            for (int i = 0; i < result.samples().size(); ++i) {
                String[] fields = lines.get(i + 1).split(",");
                for (int p = 0; p < 4; ++p) check(result.samples().get(i).raw(p) == new BigDecimal(fields[p + 2]).movePointRight(1).intValueExact(), "CSV reference temperature");
                check(result.samples().get(i).sourceFrame() == Integer.parseInt(fields[6]), "CSV reference frame grouping");
            }
            System.out.println("REC fixture: " + csv.getFileName() + ", " + result.samples().size() + " groups, " + sets.size() + " segments");
        }
        legacyOsdTests(fixtures);
        malformedSparseOsdTests(device);
        childLoaderCloseTest(jar);
        portAndUiTests(device);
        System.out.println("PASS: " + (assertions - startAssertions) + " REC import/OSD/fixture/UI assertions");
    }

    private static void verifyAndRoundTrip(TA612C device, TA612CRecDownloader.Result result, List<RecordSet> sets,
            long intervalMs, Path osd) throws Exception {
        var channel = Channels.getInstance().get(1); channel.clear();
        long expectedPoints = result.samples().stream().mapToLong(s -> Integer.bitCount(s.validMask())).sum();
        long actualPoints = 0;
        for (RecordSet records : sets) {
            check(records.getStartTimeStamp() == 0, "unknown absolute time, no download epoch");
            check(records.getRecordSetDescription().contains("NOT read from meter") && records.getRecordSetDescription().contains("completeness unverified"), "timing/completion provenance");
            for (int i = 0; i < records.getRecordDataSize(true); ++i) {
                int sample = (int) (records.getTime_ms(i) / intervalMs);
                check(records.getTime_ms(i) == (long) sample * intervalMs, "original sample timeline");
                for (int p = 0; p < 4; ++p) if ((result.samples().get(sample).validMask() & (1 << p)) != 0) {
                    check(TA612CRecImport.probe(records, p).realGet(i) == result.samples().get(sample).point(p), "present probe value retained"); ++actualPoints;
                } else check(TA612CRecImport.probe(records, p).realSize() == 0 && !TA612CRecImport.probe(records, p).isDisplayable(), "absent probe has no replacement points");
            }
            check(records.get(0).realSize() == records.getRecordDataSize(true) && !TA612CRecImport.isAbsent(records.get(0)),
                    "first present REC probe anchors UI row and graph indices");
            check(records.get(0).isScaleVisible(), "first present REC probe supplies a visible graph scale");
            channel.put(records.getName(), records);
        }
        check(actualPoints == expectedPoints, "every valid measurement preserved including partial rows");
        channel.setActiveRecordSet(sets.get(0).getName());
        OsdReaderWriter.write(osd.toString(), channel, GDE.DATA_EXPLORER_FILE_VERSION_INT);
        channel.clear(); OsdReaderWriter.read(osd.toString()); channel.setFileName(osd.toString());
        check(channel.size() == sets.size(), "OSD segment count");
        for (RecordSet source : sets) {
            RecordSet restored = channel.get(source.getName());
            if (!restored.hasDisplayableData()) restored.loadFileData(osd.toString(), false);
            check(restored.getRecordDataSize(true) == source.getRecordDataSize(true), "OSD segment rows");
            check(restored.getRecordSetDescription().equals(source.getRecordSetDescription()), "OSD retains provenance and original ranges");
            check(restored.getStartTimeStamp() == 0 && Arrays.equals(restored.getNoneCalculationRecordNames(), source.getNoneCalculationRecordNames()), "OSD unknown epoch and present probes");
            for (int p = 0; p < 4; ++p) {
                Record restoredProbe = TA612CRecImport.probe(restored, p);
                Record sourceProbe = TA612CRecImport.probe(source, p);
                check(restoredProbe.realSize() == sourceProbe.realSize(), "OSD absent/present sizes");
                for (int i = 0; i < sourceProbe.realSize(); ++i) check(restoredProbe.realGet(i).equals(sourceProbe.realGet(i)), "OSD point exact");
            }
            for (int i = 0; i < source.getRecordDataSize(true); ++i) check(restored.getTime_ms(i) == source.getTime_ms(i), "OSD inferred time exact");
            check(restored.get(0).realSize() == restored.getRecordDataSize(true) && !TA612CRecImport.isAbsent(restored.get(0)),
                    "reopened REC promotes a present UI row and graph anchor");
            check(restored.get(0).isScaleVisible(), "reopened REC retains a visible present graph scale");
            Record sourceT1 = TA612CRecImport.probe(source, 0);
            Record restoredT1 = TA612CRecImport.probe(restored, 0);
            check(!sourceT1.isDisplayable() || restoredT1.isDisplayable(), "OSD visibility");
        }
        // Save the reopened file once more to catch missing custom-property restoration.
        Path resaved = osd.resolveSibling("resaved-" + osd.getFileName());
        OsdReaderWriter.write(resaved.toString(), channel, GDE.DATA_EXPLORER_FILE_VERSION_INT);
        channel.clear(); OsdReaderWriter.read(resaved.toString()); channel.setFileName(resaved.toString());
        for (RecordSet original : sets) {
            RecordSet second = channel.get(original.getName());
            if (!second.hasDisplayableData()) second.loadFileData(resaved.toString(), false);
            check(Arrays.equals(second.getNoneCalculationRecordNames(), original.getNoneCalculationRecordNames()), "second OSD round trip retains storage mask");
            for (int p = 0; p < 4; ++p) check(TA612CRecImport.probe(second, p).realSize() == TA612CRecImport.probe(original, p).realSize(), "second OSD round trip preserves absent probes");
        }
    }

    private static void legacyOsdTests(Path fixtures) throws Exception {
        var channel = Channels.getInstance().get(1);
        int fileIndex = 0;
        for (String name : new String[] {"live-first.osd", "live-restarts.osd"}) {
            Path file = fixtures.resolve(name);
            byte[] before = Files.readAllBytes(file);
            channel.clear(); OsdReaderWriter.read(file.toString()); channel.setFileName(file.toString());
            int total = 0;
            for (String key : channel.keySet()) {
                RecordSet records = channel.get(key);
                if (!records.hasDisplayableData()) records.loadFileData(file.toString(), false);
                int count = records.getRecordDataSize(true); total += count;
                check(records.getNoneCalculationRecordNames().length == 4 && records.getStartTimeStamp() > 0, "legacy live four probes and host epoch");
                for (int i = 0; i < count; ++i) {
                    check(i == 0 || records.getTime_ms(i) >= records.getTime_ms(i - 1), "legacy live time monotonic");
                    for (int p = 0; p < 4; ++p) check(records.get(p).realSize() == count && records.get(p).realGet(i) != 2800000, "legacy live no sentinel");
                }
            }
            check(channel.size() == (fileIndex == 0 ? 1 : 5) && total == (fileIndex == 0 ? 41 : 47), "legacy instrument OSD set and row counts");
            check(Arrays.equals(before, Files.readAllBytes(file)), "legacy recording unchanged");
            System.out.println("Live OSD fixture: " + name + ", " + channel.size() + " sets, " + total + " rows");
            ++fileIndex;
        }
    }

    private static void malformedSparseOsdTests(TA612C device) throws Exception {
        RecordSet empty = RecordSet.createRecordSet("sparse rejection", device, 1, true, false, false);
        TA612CRecImport.configureMask(empty, 10);
        byte[] payload = ByteBuffer.allocate(24).putInt(0).putInt(50000).putInt(-100).putInt(0).putInt(200).putInt(2800000).array();
        expect(gde.exception.DataInconsitsentException.class, () -> device.addDataBufferAsRawDataPoints(empty, payload, 2, false), "sparse sentinel rejected atomically");
        check(TA612CRecImport.probe(empty, 1).realSize() == 0 && TA612CRecImport.probe(empty, 3).realSize() == 0,
                "sparse bad tail does not append first row");
        expect(gde.exception.DataInconsitsentException.class, () -> device.addDataBufferAsRawDataPoints(empty, Arrays.copyOf(payload, 23), 2, false), "sparse truncated OSD");
        String[] properties = new String[4];
        String[] orderedNames = empty.getRecordNames();
        int absentProperty = -1;
        for (int i = 0; i < properties.length; ++i) {
            properties[i] = empty.get(orderedNames[i]).getSerializeProperties();
            if (TA612CRecImport.isAbsent(empty.get(orderedNames[i]))) absentProperty = i;
        }
        String[] incomplete = properties.clone();
        incomplete[absentProperty] = incomplete[absentProperty].replace("ta612c_rec_present_BOOLEAN=false|", "");
        expect(IllegalArgumentException.class, () -> device.crossCheckMeasurements(incomplete, empty), "incomplete mask metadata rejected");
    }

    private static void childLoaderCloseTest(Path jar) throws Exception {
        // Real deployment loads devices through a child URLClassLoader. Core package-private
        // access would fail there even when a flat-classpath test passes.
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, TA612C.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("gde.device.tasi.")) return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            }
        }) {
            Class<?> closer = loader.loadClass("gde.device.tasi.TA612CSerialClose");
            check(closer.getClassLoader() != gde.comm.DeviceJavaSerialCommPortImpl.class.getClassLoader(), "plugin/core different class loaders");
            var backend = new gde.comm.DeviceJavaSerialCommPortImpl();
            var field = gde.comm.DeviceJavaSerialCommPortImpl.class.getDeclaredField("closeThread"); field.setAccessible(true);
            Thread closing = new Thread(() -> { try { Thread.sleep(50); } catch (InterruptedException e) { throw new AssertionError(e); } });
            field.set(backend, closing); closing.start();
            Thread.currentThread().interrupt();
            closer.getMethod("await", gde.comm.IDeviceCommPort.class).invoke(null, backend);
            check(!closing.isAlive() && Thread.interrupted(), "wait for actual close preserves cancellation interrupt across class loaders");
        }
    }

    private static void portAndUiTests(TA612C targetDevice) throws Exception {
        class ClosedPort extends TA612CSerialPort {
            int opens;
            ClosedPort() { super(targetDevice, null); }
            @Override public Object open() { ++opens; return null; }
        }
        ClosedPort live = new ClosedPort(), rec = new ClosedPort();
        live.connect();
        expect(IllegalStateException.class, rec::connect, "live blocks REC ownership");
        rec.close(); // A failed contender must not release another owner's guard.
        expect(IllegalStateException.class, rec::connect, "failed contender does not release live owner");
        live.close(); rec.connect();
        expect(IllegalStateException.class, live::connect, "REC blocks live ownership");
        rec.close(); live.connect(); live.close();
        check(live.opens == 2 && rec.opens == 1, "port ownership released after cleanup without hardware");
        Menu menu = new Menu(GDE.shell, SWT.POP_UP);
        targetDevice.addRecMenu(menu); targetDevice.addRecMenu(menu);
        check(menu.getItemCount() == 2 && menu.getItem(1).getText().contains("REC"), "REC menu installed once");
        menu.dispose();
        GDE.display.asyncExec(() -> {
            Shell dialog = GDE.display.getActiveShell();
            for (var child : dialog.getChildren()) if (child instanceof Text entry) {
                check(entry.getText().isEmpty(), "interval dialog has no default"); entry.setText("2.5");
            }
            dialog.getDefaultButton().notifyListeners(SWT.Selection, null);
        });
        check(TA612CRecDialog.open(GDE.shell) == 2500, "interval dialog accepts explicit value");
        GDE.display.asyncExec(() -> {
            Shell dialog = GDE.display.getActiveShell();
            dialog.getDefaultButton().notifyListeners(SWT.Selection, null);
            check(!dialog.isDisposed(), "blank interval cannot start download");
            for (var child : dialog.getChildren()) if (child instanceof Button button && button.getText().equals("Cancel")) button.notifyListeners(SWT.Selection, null);
        });
        check(TA612CRecDialog.open(GDE.shell) == null, "dialog cancellation has no interval");
    }

    private static TA612CRecDownloader.Result decoded(byte[]... frames) throws Exception {
        TA612CRecDecoder decoder = new TA612CRecDecoder();
        List<TA612CRecDecoder.Sample> samples = decoder.accept(join(frames)); decoder.endOfInput();
        return new TA612CRecDownloader.Result(samples, decoder.frames(), decoder.emptyFrames(), 1500);
    }
    private static byte[] frame(int[][] samples) {
        ByteBuffer bytes = ByteBuffer.allocate(5 + samples.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put((byte)0x55).put((byte)0xaa).put((byte)2).put((byte)(3 + samples.length * 8));
        for (int[] row : samples) for (int point : row) bytes.putShort((short)point);
        int sum = 0; for (int i = 0; i < bytes.position(); ++i) sum += bytes.get(i) & 0xff;
        bytes.put((byte)sum); return bytes.array();
    }
    private static byte[] join(byte[]... bytes) { ByteArrayOutputStream out = new ByteArrayOutputStream(); for (byte[] part : bytes) out.writeBytes(part); return out.toByteArray(); }
    private static Capture run(Fake port) throws Exception { return run(port, 40, 30, 2000, 100000); }
    private static Capture run(Fake port, long first, long quiet, long maximum, int count) throws Exception {
        Capture capture = new Capture(); TA612CRecDownloader worker = new TA612CRecDownloader(port, capture, first, quiet, maximum, count);
        worker.start(); worker.join(3000);
        if (worker.isAlive()) { worker.requestStop(); worker.join(1000); throw new AssertionError("REC worker failed to finish"); }
        return capture;
    }
    private static final class Fake implements TA612CRecDownloader.Transport {
        final List<byte[]> batches; final CountDownLatch connected = new CountDownLatch(1);
        int opens, starts, closes, failureMode; boolean endless;
        Fake(byte[]... batches) { this.batches = new ArrayList<>(Arrays.asList(batches)); }
        public void connect() throws IOException { ++opens; connected.countDown(); if (failureMode == 1) throw new IOException("open"); }
        public void startRec() throws IOException { ++starts; if (failureMode == 2) throw new IOException("write"); }
        public byte[] readRecBytes() throws Exception {
            if (!batches.isEmpty()) return batches.remove(0);
            if (failureMode == 3) throw new IOException("disconnect after data");
            if (failureMode == 4) throw new TimeOutException("possibly discarded partial bytes");
            return endless ? GOLDEN : new byte[0];
        }
        public void close() { ++closes; if (failureMode == 5) throw new IllegalStateException("close"); }
    }
    private static final class Capture implements TA612CRecDownloader.Listener {
        TA612CRecDownloader.Result result; Exception failure; boolean cancelled;
        public void onFinished(TA612CRecDownloader.Result r, Exception e, boolean c) { result = r; failure = e; cancelled = c; }
    }
    private static void check(boolean value, String message) { ++assertions; if (!value) throw new AssertionError(message); }
    private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Throwable> type, Checked action, String message) throws Exception {
        try { action.run(); } catch (Throwable e) { check(type.isInstance(e), message + ": " + e); return; }
        throw new AssertionError(message + " did not throw");
    }
}
