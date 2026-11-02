/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import com.google.gson.GsonBuilder;
import gde.data.Channels;
import gde.data.RecordSet;
import gde.io.OsdReaderWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Codex-written device-free checks of REC time semantics and saved provenance. */
final class RecSegmentTimeTests {
    private static int assertions;
    private static final int OPEN = 28000;
    private static final Map<String,Object> receipt = new LinkedHashMap<>();

    private static void check(boolean value, String reason) {
        ++assertions;
        if (!value) throw new AssertionError(reason);
    }

    static void integration(TA612C device, Path output) throws Exception {
        int[][] rows = {
            {OPEN,OPEN,OPEN,OPEN}, {OPEN,OPEN,OPEN,OPEN},
            {OPEN,OPEN,-10,OPEN}, {OPEN,OPEN,0,OPEN},
            {OPEN,OPEN,OPEN,OPEN}, {OPEN,OPEN,OPEN,OPEN},
            {OPEN,OPEN,10,OPEN}, {OPEN,OPEN,20,OPEN},
            {OPEN,0,OPEN,-30}, {OPEN,25,OPEN,-20},
            {OPEN,OPEN,OPEN,OPEN}, {OPEN,OPEN,OPEN,OPEN}
        };
        // These expected boundaries are independent of the importer and retain
        // leading, internal and trailing no-probe groups in the recording order.
        verify(device, output, "rec-gaps", rows, 2500, new int[]{2,6,8}, new int[]{4,8,10});
        verify(device, output, "rec-one-ms", rows, 1, new int[]{2,6,8}, new int[]{4,8,10});
        verify(device, output, "rec-fractional-seconds", rows, 1001, new int[]{2,6,8}, new int[]{4,8,10});
        verify(device, output, "rec-day-interval", new int[][]{
                {OPEN,OPEN,OPEN,OPEN}, {-32768,0,32767,-1}, {-32767,1,32766,-2}},
                86400000, new int[]{1}, new int[]{3});
        verify(device, output, "rec-single-sample", new int[][]{
                {OPEN,OPEN,OPEN,OPEN}, {OPEN,OPEN,0,OPEN}, {OPEN,OPEN,OPEN,OPEN}},
                2500, new int[]{1}, new int[]{2});
        check(TA612CRecImport.prepare(device, decode(new int[][]{{OPEN,OPEN,OPEN,OPEN}}), 1, 1).isEmpty(),
                "all-open REC creates no data");
        receipt.put("assertions", assertions);
        Files.writeString(output.resolve("rec-time-result.json"), new GsonBuilder().setPrettyPrinting().create().toJson(receipt)+"\n");
        System.out.println("PASS: " + assertions + " REC segment-time/provenance/OSD assertions");
    }

    private static void verify(TA612C device, Path output, String name, int[][] raw, long interval,
            int[] first, int[] end) throws Exception {
        var decoded=decode(raw);
        var sets=TA612CRecImport.prepare(device, decoded, interval, 1);
        check(sets.size()==first.length, "expected segment count: " + name);
        var models=new ArrayList<Map<String,Object>>();
        for(int s=0;s<sets.size();s++) {
            RecordSet set=sets.get(s);
            int count=end[s]-first[s];
            check(set.getTime_ms(0)==0, "REC segment must start at zero: " + name + " segment " + s);
            check(set.getStartTimeStamp()==0, "unknown recording date, not a synthetic real date");
            check(set.getRecordDataSize(true)==count, "source sample count");
            check(set.getMaxTime_ms()==(count-1)*interval, "segment duration excludes earlier recording time");
            String description=set.getRecordSetDescription();
            String offset=java.math.BigDecimal.valueOf(first[s]*interval,3).stripTrailingZeros().toPlainString();
            check(description.contains("v2."), "new REC time provenance version");
            check(description.contains("first sample group: " + offset + " s"), "saved segment offset");
            check(description.contains("Original samples " + first[s] + ".." + (end[s]-1)), "saved source positions");
            check(description.contains("NOT read from meter") && description.contains("absolute recording time UNKNOWN")
                    && description.contains("completeness unverified"), "inference and transfer limits retained");
            var times=new ArrayList<Double>();var probes=new LinkedHashMap<String,List<Integer>>();
            for(int p=0;p<4;p++)probes.put("T"+(p+1),new ArrayList<>());
            for(int i=0;i<count;i++) {
                double actual=set.getTime_ms(i);
                check(actual==i*interval, "segment-relative sample spacing");
                check(first[s]*interval+actual==(first[s]+i)*interval, "whole-recording time reconstructed exactly");
                times.add(actual);
                for(int p=0;p<4;p++) {
                    var record=TA612CRecImport.probe(set,p);
                    if(raw[first[s]+i][p]==OPEN) {
                        check(record.realSize()==0 && !record.isDisplayable(), "absent probe stays empty/hidden");
                    } else {
                        int expected=raw[first[s]+i][p]*100;
                        check(record.realGet(i)==expected, "real signed/zero temperature retained");
                        probes.get("T"+(p+1)).add(expected);
                    }
                }
            }
            if(s>0) {
                double previousLast=(end[s-1]-1)*interval;
                double nextFirst=first[s]*interval;
                check(nextFirst-previousLast==(first[s]-end[s-1]+1)*interval,
                        "gap retains omitted sample groups and normal sample spacing");
            }
            set.updateVisibleAndDisplayableRecordsForTable();
            check(set.getDataTableRow(0,false)[0].contains("00:00.000"), "display/export row starts at zero");
            var model=new LinkedHashMap<String,Object>();
            model.put("name",set.getName());model.put("epoch",0);model.put("first_index",first[s]);
            model.put("end_index_exclusive",end[s]);model.put("offset_ms",first[s]*interval);
            model.put("interval_ms",interval);model.put("description",description);model.put("times",times);model.put("probes",probes);
            models.add(model);
        }
        var channel=Channels.getInstance().get(1);channel.clear();
        for(var set:sets)channel.put(set.getName(),set);
        channel.setActiveRecordSet(sets.get(0).getName());
        for(int round=0;round<2;round++) {
            Path file=output.resolve((round==0 ? "" : "resaved-")+name+".osd");
            OsdReaderWriter.write(file.toString(),channel,gde.GDE.DATA_EXPLORER_FILE_VERSION_INT);
            channel.clear();OsdReaderWriter.read(file.toString());channel.setFileName(file.toString());
            for(int s=0;s<sets.size();s++) {
                var saved=channel.get(sets.get(s).getName());
                if(!saved.hasDisplayableData())saved.loadFileData(file.toString(),false);
                check(saved.getStartTimeStamp()==0 && saved.getTime_ms(0)==0, "reopened unknown epoch and zero origin");
                check(saved.getRecordSetDescription().equals(models.get(s).get("description")), "saved origins/ranges survive OSD");
                check(saved.getRecordDataSize(true)==end[s]-first[s], "reopened row count");
                for(int i=0;i<end[s]-first[s];i++)check(saved.getTime_ms(i)==i*interval, "reopened relative times");
                for(int p=0;p<4;p++) {
                    var a=TA612CRecImport.probe(sets.get(s),p);var b=TA612CRecImport.probe(saved,p);
                    check(a.realSize()==b.realSize(), "reopened sparse lengths");
                    for(int i=0;i<a.realSize();i++)check(a.realGet(i).equals(b.realGet(i)), "reopened values");
                }
            }
        }
        receipt.put(name,models);
    }

    private static TA612CRecDownloader.Result decode(int[][] rows) throws Exception {
        var decoder=new TA612CRecDecoder();var samples=new ArrayList<TA612CRecDecoder.Sample>();
        for(int first=0;first<rows.length;first+=4) {
            int end=Math.min(first+4,rows.length),size=5+(end-first)*8;
            var frame=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            frame.put((byte)0x55).put((byte)0xaa).put((byte)2).put((byte)(size-2));
            for(int i=first;i<end;i++)for(int v:rows[i])frame.putShort((short)v);
            int sum=0;for(int i=0;i<size-1;i++)sum+=Byte.toUnsignedInt(frame.array()[i]);
            frame.put((byte)sum);
            // Reconstructed synthetic bytes, deliberately fragmented; not captured meter traffic.
            for(byte b:frame.array())samples.addAll(decoder.accept(new byte[]{b}));
        }
        decoder.endOfInput();
        return new TA612CRecDownloader.Result(List.copyOf(samples),decoder.frames(),decoder.emptyFrames(),1500);
    }
}
