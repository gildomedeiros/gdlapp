package au.com.gildo.vtc;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads one aiming run at a time and preserves the latest completed run. */
final class MapReplay {
    private static final long FRAME_MS=250;
    static final class Frame {
        final long ms;
        final Map<String,Object> data;
        Frame(long ms,Map<String,Object> data){this.ms=ms;this.data=data;}
    }
    final List<Frame> frames;
    private static final long RIDE_CONTEXT_MS=10000;
    static final class RideClip {
        final long rideStart,start,end;
        RideClip(long rideStart,long rideEnd,long duration){
            this.rideStart=rideStart;
            start=Math.max(0,rideStart-RIDE_CONTEXT_MS);
            end=Math.min(duration,rideEnd+RIDE_CONTEXT_MS);
        }
    }
    final long duration;
    final List<RideClip> rides;
    final double largestRange;

    private MapReplay(List<Frame> frames,long duration){
        this.frames=frames;this.duration=duration;
        List<RideClip> clips=new ArrayList<>();
        long rideStart=-1;
        double largest=0;
        for(Frame frame:frames){
            Object range=frame.data.get("fitRange");
            if(range instanceof Number)largest=Math.max(largest,((Number)range).doubleValue());
            boolean active=Boolean.TRUE.equals(frame.data.get("rideActive"));
            if(active&&rideStart<0)rideStart=frame.ms;
            else if(!active&&rideStart>=0){
                clips.add(new RideClip(rideStart,frame.ms,duration));rideStart=-1;
            }
        }
        if(rideStart>=0)clips.add(new RideClip(rideStart,duration,duration));
        rides=Collections.unmodifiableList(clips);largestRange=largest;
    }

    static MapReplay latest(File directory,File current)throws IOException {
        File[] files=directory.listFiles((d,n)->n.startsWith("VTC_")&&n.endsWith(".jsonl"));
        if(files==null)return null;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
        if(current.isFile()){
            MapReplay run=read(current);
            if(run!=null)return run;
        }
        for(File file:files)if(!file.equals(current)){
            MapReplay run=read(file);
            if(run!=null)return run;
        }
        return null;
    }

    static MapReplay read(File file)throws IOException {
        return read(new FileInputStream(file));
    }

    static MapReplay read(InputStream input)throws IOException {
        MapTimeline timeline=new MapTimeline();
        List<Frame> active=null;
        MapReplay latest=null;
        long start=0,next=0,step=FRAME_MS;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
            for(String line;(line=reader.readLine())!=null;){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(!(line.contains("\"event\":\"aim_")||line.contains("\"event\":\"gps_")
                    ||line.contains("\"event\":\"camera_zoom_")||line.contains("\"event\":\"mount_")
                    ||line.contains("\"event\":\"status_snapshot")||line.contains("\"event\":\"settings_snapshot")
                    ||line.contains("\"event\":\"calibration_")||line.contains("\"event\":\"stop_requested")
                    ||line.contains("\"event\":\"session_end")||line.contains("\"event\":\"goto_captured")
                    ||line.contains("\"event\":\"framing_observation")))continue;
                Map<?,?> row;
                try{
                    Object parsed=ConfigJson.parse(line);
                    if(!(parsed instanceof Map))continue;
                    row=(Map<?,?>)parsed;
                }catch(IllegalArgumentException partial){continue;}
                long at=MapTimeline.time(row);
                String type=String.valueOf(row.get("event"));
                if(at<0)continue;
                if(active!=null){
                    // Sample before applying this event so replay never borrows future telemetry.
                    while(next<at){
                        if(active.size()>=10000){
                            List<Frame> fewer=new ArrayList<>(active.size()/2);
                            for(int i=0;i<active.size();i+=2)fewer.add(active.get(i));
                            active=fewer;step*=2;
                        }
                        active.add(new Frame(next-start,timeline.frame(next)));
                        next+=step;
                    }
                    Object target=row.get("target");
                    boolean landmark=type.equals("goto_captured")&&target instanceof Map
                        &&"LANDMARK".equals(((Map<?,?>)target).get("kind"));
                    if(type.equals("stop_requested")||type.equals("session_end")||landmark){
                        if(!active.isEmpty()){
                            active.add(new Frame(at-start,timeline.frame(at)));
                            latest=new MapReplay(active,at-start);
                        }
                        active=null;
                    }
                }
                timeline.accept(row);
                if(type.equals("aim_start")){
                    active=new ArrayList<>();start=at;next=at;step=FRAME_MS;
                }
            }
        }
        return latest;
    }

    Map<String,Object> at(long elapsed) {
        int low=0,high=frames.size()-1;
        while(low<high){
            int mid=(low+high+1)/2;
            if(frames.get(mid).ms<=elapsed)low=mid;else high=mid-1;
        }
        return frames.get(low).data;
    }
}
