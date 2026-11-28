package au.com.gildo.fm;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class LandmarkGpsCapture {
    record Reading(LoRaTelemetry fix, long receivedMs) {}
    record Result(String coordinates, String message) {}
    interface Receiver { void read(Consumer<Reading> collect) throws IOException; }
    interface SettingsWriter { void write(String[] values) throws Exception; }
    interface Delay { void await() throws InterruptedException; }

    private final long started;
    private final ArrayDeque<Reading> samples=new ArrayDeque<>();
    private String coordinates;
    private String status="Waiting for fresh LoRa GPS · 0/10";
    private boolean cancelled, finishing;

    LandmarkGpsCapture(long started) { this.started=started; }
    synchronized void cancel() { if(!finishing) cancelled=true; }
    private synchronized boolean cancelled() { return cancelled; }
    private synchronized boolean finishBatch() { finishing=true; return !cancelled; }

    private void observe(Reading row, long now, GoTo.Trace log) {
        if(coordinates!=null || row.receivedMs()<started || now-row.receivedMs()>3000) return;
        LoRaTelemetry fix=row.fix();
        Reading last=samples.peekLast();
        if(last!=null) {
            if(last.fix().sequence==fix.sequence) return;
            if(row.receivedMs()-last.receivedMs()>3000 || fix.senderMs<last.fix().senderMs) samples.clear();
        }
        samples.addLast(row);
        if(samples.size()>10) samples.removeFirst();
        long span=row.receivedMs()-samples.getFirst().receivedMs();
        status="Stable GPS fixes "+samples.size()+"/10 · keep receiver still";
        Double spread=null;
        if(samples.size()==10 && span>=4000) {
            double lat=0, lonDelta=0, origin=samples.getFirst().fix().lon;
            for(Reading sample:samples) {
                lat+=sample.fix().lat;
                lonDelta+=Geo.delta(sample.fix().lon,origin);
            }
            lat/=samples.size();
            double lon=Geo.delta(origin+lonDelta/samples.size(),0);
            double max=0;
            for(Reading sample:samples) max=Math.max(max,Geo.distance(lat,lon,sample.fix().lat,sample.fix().lon));
            spread=max;
            status=String.format(Locale.US,"10/10 fixes · spread %.2f m · keep receiver still",max);
            if(max<=5) {
                coordinates=String.format(Locale.US,"%.8f, %.8f",lat,lon);
                java.util.List<Long> packets=new java.util.ArrayList<>();
                for(Reading sample:samples) packets.add(sample.fix().sequence);
                log.event("landmark_batch_selected","packets",new org.json.JSONArray(packets),
                    "spanMs",span,"maxDistanceM",max,"latitude",lat,"longitude",lon);
            }
        }
        log.event("landmark_gps_sample","packet",fix.sequence,"latitude",fix.lat,"longitude",fix.lon,
            "receivedMs",row.receivedMs(),"count",samples.size(),"spanMs",span,"maxDistanceM",spread);
    }

    Result run(String[] settings,Receiver receiver,LongSupplier clock,Delay delay,SettingsWriter writer,
               Consumer<String> progress,GoTo.Trace log) {
        String failure=null;
        try {
            String lastStatus="";
            while(!cancelled() && coordinates==null) {
                long now=clock.getAsLong();
                if(now-started>=120000) throw new IOException("120-second capture timeout");
                if(!samples.isEmpty() && now-samples.getLast().receivedMs()>3000) {
                    samples.clear();
                    status="Waiting for fresh LoRa GPS · 0/10";
                }
                receiver.read(row->observe(row,clock.getAsLong(),log));
                if(!status.equals(lastStatus)) { progress.accept(status); lastStatus=status; }
                if(coordinates==null && !cancelled()) delay.await();
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel();
        } catch(IOException|RuntimeException error) { failure=error.toString(); }
        if(!finishBatch()) {
            log.event("landmark_capture_result","outcome","cancelled","previousCoordinatesRetained",true);
            return new Result(null,"Capture cancelled · previous landmark retained");
        }
        if(failure==null) {
            try {
                save(settings,coordinates,writer);
                log.event("landmark_capture_result","outcome","saved","savedCoordinates",coordinates,"settingsFile",SettingsFile.LOCATION);
                return new Result(coordinates,"Saved landmark · average of 10 fixes · "+coordinates);
            } catch(Exception error) { failure="Settings write failed · "+error; }
        }
        try {
            save(settings,"",writer);
            log.event("landmark_capture_result","outcome","failed","reason",failure,"savedCoordinates","","cleared",true);
            return new Result("","Capture failed · "+failure+" · landmark cleared");
        } catch(Exception error) {
            log.event("landmark_capture_result","outcome","failed","reason",failure,"clearFailure",error.toString(),"cleared",false);
            return new Result(null,"Capture failed · "+failure+" · could not save blank landmark · "+error.getMessage());
        }
    }

    private static void save(String[] settings,String coordinates,SettingsWriter writer) throws Exception {
        String[] next=settings.clone();
        MountCoordinates.apply(coordinates,next,LandmarkCalibration.LANDMARK_LAT,LandmarkCalibration.LANDMARK_LON,
            LandmarkCalibration.COORDINATES);
        LandmarkCalibration.clearStaleReference(settings,next);
        writer.write(next);
    }
}
