/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import gde.data.Record;
import gde.data.RecordSet;
import gde.device.DataTypes;
import gde.device.IDevice;
import gde.exception.DataInconsitsentException;
import gde.utils.StringHelper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Prepares REC data without publishing it and maintains the sparse RecordSet
 * representation shared with live acquisition. Each segment has a fixed probe
 * presence mask and retains all four physical probe definitions, but stores
 * points only for present probes. Missing readings never become temperatures.
 * RecordSet operations belong to the caller's DataExplorer/UI context; this
 * class performs no transport operations.
 */
public final class TA612CRecImport {
    // Retain the historical REC name: persisted sparse live OSD files use it too.
    public static final String PRESENT = "ta612c_rec_present";
    /** Utility class: sparse storage has no independent instance state. */
    private TA612CRecImport() { }

    /**
     * Converts explicit user-entered seconds to exact integral milliseconds,
     * accepting either decimal separator. Bounds are 1 ms through one day;
     * non-integral milliseconds are rejected rather than silently rounded.
     * This value is never read from or written to the meter.
     */
    public static long parseIntervalMs(String seconds) {
        try {
            long value = new BigDecimal(seconds.trim().replace(',', '.')).movePointRight(3).longValueExact();
            if (value < 1 || value > 86400000) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Enter the recording interval in seconds (0.001 to 86400, at most three decimals).", e);
        }
    }

    /**
     * Builds all fixed-mask segments before the caller inserts any into a channel.
     * Elapsed time uses the original zero-based sample index times intervalMs,
     * preserving offsets across all-open ranges; epoch zero explicitly denotes
     * an unknown recording date. Empty/all-open input yields no RecordSets and
     * does not prove empty meter memory. The result's completeness caveat is
     * copied into every segment's persisted description.
     *
     * @param firstNumber number used for the first generated RecordSet name
     * @throws IllegalArgumentException on invalid interval, OSD time overflow or
     *         excessive segmentation; no prepared sets have been published
     * @throws DataInconsitsentException if the core rejects prepared point data
     */
    public static List<RecordSet> prepare(TA612C device, TA612CRecDownloader.Result result, long intervalMs, int firstNumber)
            throws DataInconsitsentException {
        if (intervalMs < 1 || intervalMs > 86400000) throw new IllegalArgumentException("Invalid REC interval");
        List<TA612CRecDecoder.Sample> samples = result.samples();
        // The core OSD writer truncates variable times to signed int32 in 0.1 ms.
        if ((long) Math.max(0, samples.size() - 1) * intervalMs > Integer.MAX_VALUE / 10L)
            throw new IllegalArgumentException("Inferred REC span exceeds the supported variable-time OSD range (about 59.6 hours)");
        String absentRanges = allAbsentRanges(samples);
        List<RecordSet> prepared = new ArrayList<>();
        for (int first = 0; first < samples.size();) {
            int mask = samples.get(first).validMask();
            int end = first + 1;
            while (end < samples.size() && samples.get(end).validMask() == mask) ++end;
            if (mask != 0) {
                if (prepared.size() >= 1000) throw new IllegalArgumentException("REC segment limit exceeded (1000); nothing imported");
                String name = (firstNumber + prepared.size()) + ") REC inferred " + probeNames(mask);
                RecordSet records = RecordSet.createRecordSet(name, device, 1, true, false, false);
                configureMask(records, mask);
                records.setTimeStep_ms(-1);
                records.setStartTimeStamp(0); // Explicit unknown epoch placeholder, never download time.
                records.setRecordSetDescription("TA612C REC v1. INFERRED TIME; absolute recording time UNKNOWN. "
                        + "Interval " + BigDecimal.valueOf(intervalMs, 3).stripTrailingZeros().toPlainString()
                        + " s supplied by user, NOT read from meter. elapsed = original zero-based sample index * interval. "
                        + "OSD epoch 0 (1970) is an unknown-time placeholder; absolute-time views are not recording dates. "
                        + "Original samples " + first + ".." + (end - 1) + " of " + samples.size()
                        + "; source REC frames " + samples.get(first).sourceFrame() + ".." + samples.get(end - 1).sourceFrame()
                        + "; present probes " + probeNames(mask) + ". Other probes absent, no replacement points. "
                        + "All-probes-absent sample ranges: " + absentRanges + ". "
                        + result.completionDescription() + " Frames " + result.frames() + ", empty frames " + result.emptyFrames() + ".");
                for (int i = first; i < end; ++i) {
                    int[] points = new int[Integer.bitCount(mask)];
                    int column = 0;
                    for (int p = 0; p < 4; ++p) if ((mask & (1 << p)) != 0) points[column++] = samples.get(i).point(p);
                    records.addTimeStep_ms((long) i * intervalMs);
                    records.addNoneCalculationRecordsPoints(points);
                }
                device.makeInActiveDisplayable(records);
                records.syncScaleOfSyncableRecords();
                prepared.add(records);
            }
            first = end;
        }
        return List.copyOf(prepared);
    }

    /**
     * Configures a nonempty fixed-mask segment before points are appended or an
     * OSD payload is sized. Stored columns remain in physical T1..T4 order even
     * if the UI name order changes. Presence is persisted independently of user
     * visibility; applying a different mask to populated data is not supported.
     */
    static void configureMask(RecordSet records, int mask) {
        if (records.size() != 4 || mask < 1 || mask > 15) throw new IllegalArgumentException("Invalid REC probe mask");
        List<String> stored = new ArrayList<>();
        for (int p = 0; p < 4; ++p) {
            Record record = probe(records, p);
            boolean present = (mask & (1 << p)) != 0;
            if (record.getProperty(PRESENT) == null) record.createProperty(PRESENT, DataTypes.BOOLEAN, present);
            else record.getProperty(PRESENT).setValue(Boolean.toString(present));
            record.setActive(present);
            record.setDisplayable(present);
            if (present) stored.add(record.getName());
        }
        records.setNoneCalculationRecordNames(stored.toArray(String[]::new));
        promoteFirstPresentProbe(records, mask);
        configureSparseScaleSync(records, mask);
    }

    /**
     * DataExplorer 4.0.7 uses recordNames[0] as its row/time index and graph-time
     * anchor. Keep all four probe records, but put a genuinely present probe first
     * for sparse segments. Record ordinals and probe values are not changed.
     */
    static void promoteFirstPresentProbe(RecordSet records, int mask) {
        int firstPresent = Integer.numberOfTrailingZeros(mask);
        String firstName = probe(records, firstPresent).getName();
        if (records.getRecordNames()[0].equals(firstName)) return;
        for (String name : records.getRecordNames()) records.removeRecordName(name);
        records.addRecordName(firstName);
        for (int p = 0; p < 4; ++p) if (p != firstPresent) records.addRecordName(probe(records, p).getName());
    }

    /**
     * Gives every sparse segment independent scales, avoiding an absent XML
     * master T1. The integer -1 survives OSD serialization; an empty integer
     * property cannot be read by the 4.0.7 loader. All-present sets keep the
     * normal XML synchronization. Call before using a segment's graph scales.
     */
    static void configureSparseScaleSync(RecordSet records, int mask) {
        if (mask == 15) return; // Preserve the device XML's normal all-probe scale synchronization.
        for (int p = 0; p < 4; ++p) {
            Record record = probe(records, p);
            if (record.getProperty(IDevice.SYNC_ORDINAL) == null) {
                record.createProperty(IDevice.SYNC_ORDINAL, DataTypes.INTEGER, -1);
            } else {
                record.getProperty(IDevice.SYNC_ORDINAL).setValue(-1);
            }
        }
    }

    /** Finds a physical zero-based probe ordinal regardless of UI name order. */
    static Record probe(RecordSet records, int ordinal) {
        for (Record record : records.getValues()) if (record.getOrdinal() == ordinal) return record;
        throw new IllegalArgumentException("TA612C probe ordinal missing: " + ordinal);
    }

    /**
     * Restores explicit storage presence before the core sizes a lazy OSD payload.
     * Resolves properties by saved record name to preserve physical probe identity
     * despite UI reordering, and returns names in incoming property order for the
     * core loader. No markers means a legacy four-column file; partial, duplicate
     * per-record or malformed markers are rejected. Visibility is never evidence
     * of whether a measurement column exists on disk.
     */
    static String[] restoreMask(String[] properties, RecordSet records) {
        if (properties.length != 4 || records.size() != 4) throw new IllegalArgumentException("TA612C OSD requires four probe definitions");
        int mask = 0, markers = 0;
        String[] recordKeys = new String[properties.length];
        for (int i = 0; i < properties.length; ++i) {
            HashMap<String, String> standard = StringHelper.splitString(properties[i], Record.DELIMITER, Record.propertyKeys);
            String recordKey = standard.get(Record.NAME);
            Record record = records.get(recordKey);
            if (record == null) throw new IllegalArgumentException("Unknown TA612C OSD probe: " + recordKey);
            int p = record.getOrdinal();
            recordKeys[i] = recordKey;
            String value = null;
            for (String entry : properties[i].split("\\|")) {
                if (entry.startsWith(PRESENT + "_")) {
                    if (value != null) throw new IllegalArgumentException("Duplicate REC probe marker");
                    // DataTypes.toString() is BOOLEAN in this version of the core.
                    if (!entry.equals(PRESENT + "_BOOLEAN=true") && !entry.equals(PRESENT + "_BOOLEAN=false"))
                        throw new IllegalArgumentException("Invalid REC probe marker");
                    value = entry.substring(entry.indexOf('=') + 1);
                }
            }
            if (value != null) { ++markers; if (value.equals("true")) mask |= 1 << p; }
        }
        if (markers != 0 && markers != 4) throw new IllegalArgumentException("Incomplete REC probe markers");
        if (markers == 4) configureMask(records, mask);
        return recordKeys;
    }

    /** Only an explicit false marker denotes absence; legacy records lack it. */
    static boolean isAbsent(Record record) {
        return record.getProperty(PRESENT) != null && "false".equals(record.getProperty(PRESENT).getValue());
    }
    /** Formats a presence mask as physical names such as T2+T4 for provenance. */
    static String probeNames(int mask) {
        List<String> names = new ArrayList<>();
        for (int p = 0; p < 4; ++p) if ((mask & (1 << p)) != 0) names.add("T" + (p + 1));
        return String.join("+", names);
    }
    /**
     * Lists inclusive, zero-based all-open sample ranges for every segment's
     * description. These groups create no points but still occupy inferred time.
     */
    private static String allAbsentRanges(List<TA612CRecDecoder.Sample> samples) {
        List<String> ranges = new ArrayList<>();
        for (int i = 0; i < samples.size(); ++i) if (samples.get(i).validMask() == 0) {
            int first = i;
            while (i + 1 < samples.size() && samples.get(i + 1).validMask() == 0) ++i;
            ranges.add(first == i ? "" + first : first + ".." + i);
        }
        return ranges.isEmpty() ? "none" : String.join(", ", ranges);
    }
}
