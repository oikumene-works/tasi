/* SPDX-License-Identifier: GPL-3.0-or-later */
package gde.device.tasi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import gde.GDE;
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
import java.util.function.LongSupplier;

/** Codex-written software tests. No real transport, meter timestamps or human observations. */
final class LiveSegmentTimeTests {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static int assertions;
    static double maximumAbsoluteErrorMs;
    static double maximumGapErrorMs;
    record Input(long nanos, int[] raw) {}
    record Scenario(String name, long epoch, List<Input> input) {}
    record Emission(Input input, TA612CFrameDecoder.Sample sample, double time, long epoch) {}

    static void check(boolean condition, String message) {
        ++assertions;
        if (!condition) throw new AssertionError(message);
    }

    static List<Scenario> scenarios() throws Exception {
        List<Scenario> cases = new ArrayList<>();
        int[] open = {28000,28000,28000,28000};
        cases.add(new Scenario("leading-gap-same-mask-return", 1700000000000L, List.of(
                new Input(0,open), new Input(123_456_789,open),
                new Input(30_758_500_000L,new int[]{28000,-10,28000,0}),
                new Input(31_000_000_001L,new int[]{28000,0,28000,-20}),
                new Input(40_000_000_000L,open), new Input(50_000_000_000L,open),
                new Input(53_822_700_000L,new int[]{28000,-30,28000,0}),
                new Input(53_822_749_999L,new int[]{28000,-40,28000,1}),
                new Input(130_652_700_000L,new int[]{28000,0,28000,-50}),
                new Input(140_000_000_000L,open))));
        cases.add(new Scenario("all-open", 1700000000000L,
                List.of(new Input(0,open),new Input(9_000_000_000L,open))));
        List<Input> masks = new ArrayList<>();
        long nanos=0;
        for (int mask=1; mask<16; ++mask) {
            for (int i=0; i<3; ++i) {
                int[] raw=open.clone();
                for (int p=0; p<4; ++p) if ((mask & (1<<p))!=0) raw[p]=(i-1)*(p+1)*10;
                masks.add(new Input(nanos,raw)); nanos += 1_234_567_891L;
            }
            masks.add(new Input(nanos,open)); nanos+=3_456_789_123L;
        }
        cases.add(new Scenario("all-fifteen-masks",1700000000000L,masks));
        List<Input> rounding = new ArrayList<>();
        // Both sides of millisecond and 0.1 ms boundaries, including two samples
        // in a single storage tick and enough elapsed time to cross nanoTime zero.
        for(int i=0;i<800;i++) {
            long time=i*2_000_000_000L + (i%20)*50_001L;
            rounding.add(new Input(time, i%4<2 ? new int[]{-10,28000,0,28000}
                                                      : new int[]{28000,0,28000,-10}));
        }
        cases.add(new Scenario("rounding-and-clock-wrap",1700000000000L,rounding));
        return cases;
    }

    static List<Emission> replay(Scenario scenario) throws Exception {
        var samples=scenario.input.stream().map(i->TA612CFrameDecoder.decode(frame(i.raw))).toList();
        class Clock implements LongSupplier {
            int reads;
            public long getAsLong() {
                int index=reads++;
                return Long.MAX_VALUE-5_000_000_000L + (index==0 ? 0 : scenario.input.get(index-1).nanos);
            }
        }
        class Port implements TA612CGathererThread.Transport {
            int opens,starts,reads,closes;
            public void connect(){++opens;}
            public void startLive(){++starts;}
            public List<TA612CFrameDecoder.Sample> readSamples(){++reads; return samples;}
            public void close(){++closes;}
        }
        Port port=new Port(); Clock clock=new Clock(); int[] wallReads={0};
        List<Emission> result=new ArrayList<>(); Exception[] failure={null};
        TA612CGathererThread[] worker=new TA612CGathererThread[1];
        worker[0]=new TA612CGathererThread(port,new TA612CGathererThread.Listener(){
            public void onSample(TA612CFrameDecoder.Sample sample,double elapsed,long epoch){
                result.add(new Emission(scenario.input.get(result.size()),sample,elapsed,epoch));
                if(result.size()==samples.size())worker[0].requestStop();
            }
            public void onStopped(String message,Exception error){failure[0]=error;}
        },1500,3,clock,()->scenario.epoch + (wallReads[0]++==0 ? 0 : 3_600_000));
        worker[0].start(); worker[0].join(5000);
        if(worker[0].isAlive()) { worker[0].requestStop(); worker[0].join(1000); throw new AssertionError("worker timeout"); }
        check(failure[0]==null,"fake worker completes: "+scenario.name+": "+failure[0]);
        check(result.size()==samples.size(),"every decoded frame delivered");
        check(wallReads[0]==1,"host-clock corrections cannot shift later segments");
        check(port.opens==1 && port.starts==1 && port.reads==1 && port.closes==1,"one transport lifecycle");
        int mask=-1; long epoch=0; double previous=-1;
        for(Emission e:result) {
            if(mask!=e.sample.validMask()) {
                mask=e.sample.validMask(); epoch=e.epoch; previous=-1;
                check(e.time==0.0,"each mask segment begins at exactly zero: "+scenario.name);
            }
            check(e.epoch==epoch,"one epoch per segment");
            check(Double.isFinite(e.time) && e.time>=previous,"relative time never decreases within segment");
            previous=e.time;
            double error=(e.epoch-scenario.epoch)+e.time-e.input.nanos/1_000_000.0;
            check(Math.abs(error)<=0.500001,"callback absolute time within epoch rounding: "+error);
            for(int p=0;p<4;p++)if(e.sample.isValid(p))
                check(e.sample.raw(p)==e.input.raw[p],"decoded sample unchanged");
        }
        return result;
    }

    static void protocol() throws Exception {
        for(Scenario scenario:scenarios())replay(scenario);
        System.out.println("PASS: "+assertions+" deterministic live-clock assertions");
    }

    static List<Map<String,Object>> snapshot(List<RecordSet> sets) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(RecordSet set:sets){
            Map<String,Object> model=new LinkedHashMap<>(); model.put("name",set.getName());
            model.put("epoch",set.getStartTimeStamp()); model.put("description",set.getRecordSetDescription());
            List<Double> times=new ArrayList<>();
            for(int i=0;i<set.getRecordDataSize(true);i++)times.add(set.getTime_ms(i));
            model.put("times",times); Map<String,Object> probes=new LinkedHashMap<>();
            for(int p=0;p<4;p++) {
                var probe=TA612CRecImport.probe(set,p); List<Integer> values=new ArrayList<>();
                for(int i=0;i<probe.realSize();i++)values.add(probe.realGet(i));
                probes.put("T"+(p+1),Map.of("values",values,"absent",TA612CRecImport.isAbsent(probe),"displayable",probe.isDisplayable()));
            }
            model.put("probes",probes);result.add(model);
        }
        return result;
    }

    static List<RecordSet> read(Path path) throws Exception {
        var channel=Channels.getInstance().get(1);channel.clear();
        OsdReaderWriter.read(path.toString());channel.setFileName(path.toString());
        List<RecordSet> result=new ArrayList<>();
        for(String name:channel.getRecordSetNames()) {
            RecordSet set=channel.get(name);
            if(!set.hasDisplayableData())set.loadFileData(path.toString(),false);
            result.add(set);
        }
        return result;
    }

    static void integration(TA612C device,Path output,Path jar) throws Exception {
        check(Path.of(TA612CGathererThread.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()
                .equals(jar.toRealPath()),"test executes the packaged standalone worker");
        Map<String,Object> receipt=new LinkedHashMap<>();
        receipt.put("worker_location",jar.toRealPath().toString());
        for(Scenario scenario:scenarios()) {
            var emissions=replay(scenario); List<RecordSet> sets=new ArrayList<>();
            RecordSet current=null; int mask=-1; double previousActual=0,previousExpected=0;boolean havePrevious=false;
            for(Emission e:emissions) {
                if(mask!=e.sample.validMask()) {
                    mask=e.sample.validMask();current=null;
                    if(mask!=0) {
                        current=RecordSet.createRecordSet((sets.size()+1)+") "+scenario.name,device,1,true,false,true);
                        TA612C.configureLiveSegment(current,mask,e.epoch);sets.add(current);
                    }
                }
                if(current==null)continue;
                TA612C.appendLiveSample(current,e.sample,e.time);
                int i=current.getRecordDataSize(true)-1;
                check(i!=0 || current.getTime_ms(i)==0,"stored segment starts at zero");
                double actual=(current.getStartTimeStamp()-scenario.epoch)+current.getTime_ms(i);
                double expected=e.input.nanos/1_000_000.0;
                double error=Math.abs(actual-expected);maximumAbsoluteErrorMs=Math.max(maximumAbsoluteErrorMs,error);
                check(error<=0.600001,"stored absolute time tolerance: "+scenario.name+": "+error);
                if(havePrevious) {
                    double gapError=Math.abs((actual-previousActual)-(expected-previousExpected));
                    maximumGapErrorMs=Math.max(maximumGapErrorMs,gapError);
                    check(gapError<=1.200001,"gap duration tolerance: "+gapError);
                }
                previousActual=actual;previousExpected=expected;havePrevious=true;
                for(int p=0;p<4;p++) {
                    var probe=TA612CRecImport.probe(current,p);
                    if(e.sample.isValid(p))check(probe.realGet(i)==e.input.raw[p]*100,"stored physical value unchanged");
                    else check(probe.realSize()==0 && !probe.isDisplayable(),"absent probes empty and hidden");
                }
            }
            if(sets.isEmpty()) {check(scenario.name.equals("all-open"),"only all-open case has no sets");continue;}
            var channel=Channels.getInstance().get(1);channel.clear();
            for(RecordSet set:sets){device.makeInActiveDisplayable(set);channel.put(set.getName(),set);}
            channel.setActiveRecordSet(sets.get(0).getName());
            var before=snapshot(sets);
            Path osd=output.resolve(scenario.name+".osd");
            OsdReaderWriter.write(osd.toString(),channel,GDE.DATA_EXPLORER_FILE_VERSION_INT);
            var after=snapshot(read(osd));
            check(JSON.toJson(before).equals(JSON.toJson(after)),"OSD retains times, epochs, values and sparse state: "+scenario.name);
            receipt.put(scenario.name,after);
        }
        for(String name:List.of("live-first.osd","live-restarts.osd")) {
            Path file=Path.of("test/fixtures",name).toAbsolutePath();byte[] before=Files.readAllBytes(file);
            receipt.put(name,snapshot(read(file)));
            check(Arrays.equals(before,Files.readAllBytes(file)),"legacy file not rewritten");
        }
        receipt.put("maximum_absolute_error_ms",maximumAbsoluteErrorMs);
        receipt.put("maximum_gap_error_ms",maximumGapErrorMs);
        receipt.put("assertions",assertions);
        Files.writeString(output.resolve("live-time-result.json"),JSON.toJson(receipt)+"\n");
        System.out.println("PASS: "+assertions+" live time and OSD assertions; maximum absolute error="
                +maximumAbsoluteErrorMs+" ms, gap error="+maximumGapErrorMs+" ms");
    }

    static byte[] frame(int[] raw) {
        var b=ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)0x55).put((byte)0xaa).put((byte)1).put((byte)11);
        for(int value:raw)b.putShort((short)value);
        int sum=0;for(int i=0;i<12;i++)sum+=b.array()[i]&255;
        b.put((byte)sum);return b.array();
    }
}
