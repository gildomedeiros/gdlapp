package au.com.gildo.fm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class HighSpeedReplay {
    private static final long SAMPLE_INTERVAL_MS=100, STOP_TIMEOUT_MS=30_000, RUN_MARGIN_MS=5_000;

    record TimedPoint(GoTo.Point point,long delayMs) {}
    record Checkpoint(GoTo.Point point,int count,long relativeCount,double rangeM,double distanceM,
                      long dueMs,double targetPaceKmh,double requiredCountsPerSecond) {
        Double matchedPaceKmh(double rate) {
            return requiredCountsPerSecond>1e-6?targetPaceKmh*rate/requiredCountsPerSecond:null;
        }

        double distanceGapM(double panCount,int cpr) {
            return Geo.panGapM(rangeM,panCount-relativeCount,cpr);
        }
    }
    record Plan(List<Checkpoint> checkpoints,GoTo.Reference reference,int cpr,int direction,
                double countsPerSecond,long runLimitMs) {
        Checkpoint last() { return checkpoints.get(checkpoints.size()-1); }
        double targetPaceKmh() { return last().distanceM()*3600/last().dueMs(); }
        boolean ceilingApplied() { return countsPerSecond<checkpoints.get(0).requiredCountsPerSecond(); }
        double capRate(double rate) { return Math.min(rate,GoTo.MAX_HS_SIDEREAL*cpr/GoTo.SIDEREAL_DAY_SECONDS); }
    }

    record SpeedUp(double multiplier,boolean matchCheckpoint) {
        static final SpeedUp NONE=new SpeedUp(1,false);
        static final SpeedUp MATCH_CHECKPOINT=new SpeedUp(1,true);

        static SpeedUp parse(String field) {
            String value=field.trim();
            if(value.isEmpty()||value.equals("0")) return NONE;
            if(value.equals("1")) return MATCH_CHECKPOINT;
            if(!value.matches("[0-9]+(?:\\.[0-9]+)?[xX]?"))
                throw new IllegalArgumentException("Use a multiplier such as 1.5x");
            int last=value.length()-1;
            if(value.charAt(last)=='x'||value.charAt(last)=='X') value=value.substring(0,last);
            double multiplier=Double.parseDouble(value);
            if(!Double.isFinite(multiplier)||multiplier<1)
                throw new IllegalArgumentException("Use a multiplier of 1.0x or greater");
            return multiplier==1?NONE:new SpeedUp(multiplier,false);
        }

        boolean requested() { return matchCheckpoint||multiplier>1; }

        double targetRate(Plan plan,int index,double current) {
            double requested=matchCheckpoint?plan.checkpoints().get(index).requiredCountsPerSecond():current*multiplier;
            return plan.capRate(requested);
        }
    }

    record Observation(long elapsedMs,long wallTimeMs,int count,double rate) {}
    record Progress(int simulatedRow,int observed,int total,long elapsedMs,double lead,long nextDueMs) {}

    private static final class SpeedChange {
        final int index,period;
        final double rateBefore,rateAfter;
        String outcome="Speed-up requested";
        PanStop stop;
        Long restartSentMs,restartAckMs,resumedMs;

        SpeedChange(int index,double rateBefore,double rateAfter,int period) {
            this.index=index;
            this.rateBefore=rateBefore;
            this.rateAfter=rateAfter;
            this.period=period;
        }
    }

    static final class Result {
        final Observation[] observed;
        final Double[] distanceGapsM;
        final SpeedChange[] speedChanges;
        int measuredSpeedSegments;
        String reason="HS Start not sent";
        String stopReply="Not sent";
        Long startMs,startAckMs,wallStartMs,stopSentMs,stopAckMs,stoppedMs;
        Integer lastSampleCount,finalCount;
        boolean reachedLast;

        Result(int checkpoints) {
            observed=new Observation[checkpoints];
            distanceGapsM=new Double[checkpoints];
            speedChanges=new SpeedChange[checkpoints];
        }
        String outcome() {
            if(reachedLast && stoppedMs!=null && finalCount!=null && stopAckMs!=null)
                return "HS replay complete";
            if(startMs==null) return "HS replay blocked · "+reason;
            return "HS replay ended · "+reason+(stopAckMs==null?" · Stop reply unconfirmed":"");
        }
    }

    private HighSpeedReplay() {}

    static Plan plan(List<TimedPoint> points,GoTo.Reference reference,int cpr) throws IOException {
        if(points.size()<2) throw new IOException("HS replay needs at least two viewpoints");
        if(points.get(0).delayMs()!=0) throw new IOException("HS viewpoint 1 requires delay_ms = 0");
        GoTo.Point mount=reference.pivot();
        int size=points.size(),direction=0;
        int[] target=new int[size];
        double[] range=new double[size],bearing=new double[size],distance=new double[size],rates=new double[size];
        long[] relative=new long[size],due=new long[size];
        double[] legPaceKmh=new double[size-1];
        for(int i=0;i<size;i++) {
            TimedPoint timed=points.get(i);
            GoTo.Point point=timed.point();
            range[i]=distance(mount,point);
            if(range[i]<0.1) throw new IOException("Viewpoint "+(i+1)+" is too close to define a pan target");
            bearing[i]=Geo.bearing(mount.lat(),mount.lon(),point.lat(),point.lon());
            target[i]=GoTo.panTarget(bearing[i],reference,cpr);
            if(i==0) continue;
            if(timed.delayMs()<=0) throw new IOException("HS viewpoint "+(i+1)+" requires a positive delay_ms");
            double legDistance=distance(points.get(i-1).point(),point);
            due[i]=due[i-1]+timed.delayMs();
            distance[i]=distance[i-1]+legDistance;
            legPaceKmh[i-1]=legDistance*3600/timed.delayMs();
            int step=GoTo.delta(target[i],target[i-1]);
            relative[i]=relative[i-1]+step;
            int sign=Integer.signum(step);
            if(sign!=0) {
                if(direction!=0 && direction!=sign) throw new IOException("HS pan would reverse at viewpoint "+(i+1));
                direction=sign;
            }
        }
        if(direction==0) throw new IOException("The viewpoints need to move in one pan direction");

        for(int i=0;i<size;i++) {
            GoTo.Point point=points.get(i).point();
            double heading=i<size-1?trackBearing(point,points.get(i+1).point()):
                trackBearing(point,points.get(i-1).point())+180;
            double across=Math.abs(Math.sin(Math.toRadians(heading-bearing[i])));
            double paceMps=legPaceKmh[Math.min(i,size-2)]/3.6;
            rates[i]=paceMps*across*cpr/(2*Math.PI*range[i]);
        }
        double sidereal=rates[0]*GoTo.SIDEREAL_DAY_SECONDS/cpr;
        if(!Double.isFinite(sidereal)||sidereal<GoTo.MIN_HS_SIDEREAL)
            throw new IOException(String.format(java.util.Locale.US,
                "Viewpoint 1 needs %.0f× at %.2f km/h; the HS lower limit is %d×",sidereal,legPaceKmh[0],GoTo.MIN_HS_SIDEREAL));
        double rate=Math.min(rates[0],GoTo.MAX_HS_SIDEREAL*cpr/GoTo.SIDEREAL_DAY_SECONDS);

        List<Checkpoint> checkpoints=new ArrayList<>(size);
        for(int i=0;i<size;i++) {
            checkpoints.add(new Checkpoint(points.get(i).point(),target[i],relative[i],range[i],distance[i],
                due[i],legPaceKmh[Math.min(i,size-2)],rates[i]));
        }
        long runLimit=Math.round(Math.max(checkpoints.get(size-1).dueMs(),Math.abs(relative[size-1])*1_000/rate))
            +RUN_MARGIN_MS;
        return new Plan(java.util.Collections.unmodifiableList(checkpoints),reference,cpr,direction,rate,runLimit);
    }

    static Result goToPositionHS(GoTo mount,Plan plan,GoTo.PanTiming timing,int period,SpeedUp[] requestedSpeedUps,
                                 BooleanSupplier active,Runnable tick,Consumer<Progress> progress,GoTo.Trace log)
        throws IOException {
        Result result=new Result(plan.checkpoints().size());
        Checkpoint first=plan.checkpoints().get(0);
        GoTo.PanSample baseline=mount.readPan();
        Sweep sweep=new Sweep(baseline.count(),GoTo.delta(baseline.count(),first.count()),period,plan.countsPerSecond());
        if(plan.direction()*(plan.last().relativeCount()-sweep.actual)<=0)
            throw new IOException("Final HS checkpoint is not ahead of the lined-up pan");
        result.lastSampleCount=baseline.count();
        mount.prepareHighSpeedPan(plan.direction(),period);
        if(!active.getAsBoolean()) throw new IOException("HS replay cancelled before Start");
        if(requestedSpeedUps[0].requested()) skipSpeedUp(result,0,sweep.rate,sweep.rate,"Viewpoint 1 supplies the initial speed");
        BenchSegment bench=new BenchSegment(sweep.actual);
        SpeedChange pendingResume=null;
        PanStop lastStop=null;
        boolean abort=false;
        try {
            result.startAckMs=mount.startAxis(1,at->{
                sweep.needsStop=true;
                result.startMs=at;
                result.wallStartMs=System.currentTimeMillis()-(GoTo.now()-at);
                result.observed[0]=new Observation(0,result.wallStartMs,baseline.count(),sweep.rate);
                sweep.measureGaps(plan,result,0,sweep.actual);
            });
            log.event("hs_measured_start","startMs",result.startMs,"wallTimeMs",result.wallStartMs,
                "startAckMs",result.startAckMs,"runLimitMs",plan.runLimitMs(),"baseline",baseline.count());
            int leg=0;
            long deadline=result.startMs+plan.runLimitMs();
            boolean movement=false;
            while(active.getAsBoolean() && !Thread.currentThread().isInterrupted() && GoTo.now()<deadline) {
                GoTo.PanSample sample=mount.readPan();
                sweep.advance(sample.count());
                bench.sample(sample.timeMs(),sweep.actual);
                result.lastSampleCount=sample.count();
                long elapsed=sample.timeMs()-result.startMs;
                sweep.measureGaps(plan,result,elapsed,sweep.actual);
                if(!movement && sample.count()!=baseline.count()) {
                    movement=true;
                    log.event("hs_first_movement","elapsedSinceStartMs",elapsed,"count",sample.count(),
                        "delta",sweep.actual-GoTo.delta(baseline.count(),first.count()));
                }
                if(pendingResume!=null&&plan.direction()*
                    GoTo.delta(sample.count(),pendingResume.stop.finalSample.count())>0) {
                    pendingResume.resumedMs=sample.timeMs();
                    pendingResume.outcome="Speed increased";
                    pendingResume=null;
                }

                while(leg+1<plan.checkpoints().size() && plan.checkpoints().get(leg+1).dueMs()<=elapsed) leg++;
                double expected=expectedCount(plan,leg,elapsed);
                double lead=plan.direction()*(sweep.actual-expected);
                log.event("hs_pan_sample","sampleAtMs",sample.timeMs(),"elapsedSinceStartMs",elapsed,"count",sample.count(),
                    "relativeCount",sweep.actual,"expectedRelativeCount",expected,"leadCounts",lead,
                    "commandedCountsPerSecond",sweep.rate);
                int from=sweep.next;
                observe(plan,result,sweep,sample);
                if(sweep.next==plan.checkpoints().size()) {
                    skipPassedSpeedUps(plan,result,sweep,requestedSpeedUps,from,"Final viewpoint reached");
                    reachedLast(result);
                    break;
                }

                SpeedChange change=chooseSpeedUp(plan,result,sweep,requestedSpeedUps,from,timing);
                if(change!=null) {
                    change.stop=lastStop=stop(mount,tick,log);
                    recordBench(result,bench,sweep,lastStop,plan.checkpoints().get(change.index),plan.direction(),log);
                    sweep.needsStop=false;
                    if(lastStop.finalSample!=null) {
                        sweep.advance(lastStop.finalSample.count());
                        sweep.measureGaps(plan,result,lastStop.finalSample.timeMs()-result.startMs,sweep.actual);
                        int beforeCoast=sweep.next;
                        observe(plan,result,sweep,lastStop.finalSample);
                        skipPassedSpeedUps(plan,result,sweep,requestedSpeedUps,beforeCoast,"Passed during Stop");
                    }
                    if(!lastStop.usable()) {
                        change.outcome="Stop unsuccessful · "+lastStop.problem();
                        result.reason="Speed-up at viewpoint "+(change.index+1)+" · "+change.outcome;
                        abort=true;
                        break;
                    }
                    if(sweep.next==plan.checkpoints().size()) {
                        change.outcome="Final viewpoint reached while stopping";
                        reachedLast(result);
                        break;
                    }
                    if(!active.getAsBoolean()||Thread.currentThread().isInterrupted()) {
                        change.outcome="Cancelled while stopped";
                        result.reason="HS replay cancelled";
                        abort=true;
                        break;
                    }
                    pendingResume=change;
                    change.outcome="Restart preparation failed";
                    mount.prepareHighSpeedPan(plan.direction(),change.period);
                    if(!active.getAsBoolean()||Thread.currentThread().isInterrupted()) {
                        change.outcome="Cancelled before Restart";
                        result.reason="HS replay cancelled";
                        abort=true;
                        break;
                    }
                    result.lastSampleCount=lastStop.finalSample.count();
                    change.outcome="Restart reply unconfirmed";
                    change.restartAckMs=mount.startAxis(1,at->{
                        sweep.needsStop=true;
                        change.restartSentMs=at;
                        sweep.measureGaps(plan,result,at-result.startMs,sweep.actual);
                    });
                    change.outcome="Restart acknowledged · resumed motion unconfirmed";
                    deadline+=change.restartAckMs-lastStop.requestMs+RUN_MARGIN_MS;
                    sweep.period=change.period;
                    sweep.rate=change.rateAfter;
                    bench=new BenchSegment(sweep.actual);
                    continue;
                }

                progress.accept(new Progress(leg+1,sweep.next,plan.checkpoints().size(),elapsed,lead,
                    plan.checkpoints().get(sweep.next).dueMs()));
                pause(Math.min(deadline,sample.timeMs()+SAMPLE_INTERVAL_MS),active,tick);
            }
            if(!result.reachedLast&&!abort)
                result.reason=active.getAsBoolean()&&!Thread.currentThread().isInterrupted()?
                    "Timed out before the last viewpoint":"HS replay cancelled";
        } catch(IOException|RuntimeException e) {
            String prefix=result.startAckMs==null?"Start issue · ":
                pendingResume!=null?"Speed-up at viewpoint "+(pendingResume.index+1)+" · ":"Pan feedback failed · ";
            result.reason=prefix+java.util.Objects.toString(e.getMessage(),e.getClass().getSimpleName());
        } finally {
            if(sweep.needsStop) {
                lastStop=stop(mount,tick,log);
                if(result.reachedLast) recordBench(result,bench,sweep,lastStop,plan.last(),plan.direction(),log);
            }
            if(lastStop!=null) {
                finishStop(result,lastStop,log);
                if(lastStop.finalSample!=null) {
                    long elapsed=lastStop.finalSample.timeMs()-result.startMs;
                    long stoppedCount=sweep.actual+GoTo.delta(lastStop.finalSample.count(),sweep.count);
                    sweep.measureGaps(plan,result,elapsed,stoppedCount);
                    if(result.reachedLast&&elapsed<plan.last().dueMs())
                        sweep.measureGaps(plan,result,plan.last().dueMs(),stoppedCount);
                }
            }
            for(SpeedChange change:result.speedChanges) if(change!=null)
                log.event("hs_speed_up_result","viewpoint",change.index+1,"outcome",change.outcome,
                    "beforeCountsPerSecond",change.rateBefore,"targetCountsPerSecond",change.rateAfter,
                    "multiplier",requestedSpeedUps[change.index].matchCheckpoint()?null:requestedSpeedUps[change.index].multiplier(),
                    "legacyMatchCheckpoint",requestedSpeedUps[change.index].matchCheckpoint(),
                    "restartStepPeriod",change.period>0?change.period:null,
                    "stopRequestedMs",change.stop==null?null:change.stop.requestMs,
                    "stopAckMs",change.stop==null?null:change.stop.ackMs,
                    "stoppedMs",change.stop==null?null:change.stop.stoppedMs,
                    "restartSentMs",change.restartSentMs,"restartAckMs",change.restartAckMs,"resumedMs",change.resumedMs);
        }
        return result;
    }

    private static void recordBench(Result result,BenchSegment segment,Sweep sweep,
                                    PanStop stop,Checkpoint target,int direction,GoTo.Trace log) {
        if(!stop.usable()) return;
        Double measuredRate=segment.measuredRate(direction);
        if(measuredRate==null) return;
        long coast=GoTo.delta(stop.finalSample.count(),sweep.count);
        result.measuredSpeedSegments++;
        log.event("hs_speed_verification","period",sweep.period,"commandedCountsPerSecond",sweep.rate,
            "measuredCountsPerSecond",measuredRate,"stopToRestMs",stop.stoppedMs-stop.requestMs,
            "coastCounts",coast,"targetToRestErrorCounts",Math.abs(sweep.actual+coast-target.relativeCount()));
    }

    static final class BenchSegment {
        private final long baseline;
        private long firstMotionMs,firstMotionCount,lastMs,lastCount;
        private boolean moved;

        BenchSegment(long baseline) { this.baseline=baseline; }

        void sample(long at,long relativeCount) {
            if(!moved&&relativeCount!=baseline) {
                moved=true;
                firstMotionMs=at;
                firstMotionCount=relativeCount;
            }
            lastMs=at;
            lastCount=relativeCount;
        }

        Double measuredRate(int direction) {
            long delivered=direction*(lastCount-firstMotionCount);
            if(!moved||delivered<=0||lastMs<=firstMotionMs) return null;
            return delivered*1000.0/(lastMs-firstMotionMs);
        }
    }

    private static final class Sweep {
        long actual,previousGapMs,previousGapCount;
        int count,next=1,period,nextGap;
        double rate;
        boolean needsStop;

        Sweep(int count,long actual,int period,double rate) {
            this.count=count;
            this.actual=actual;
            this.previousGapCount=actual;
            this.period=period;
            this.rate=rate;
        }

        void advance(int nextCount) {
            actual+=GoTo.delta(nextCount,count);
            count=nextCount;
        }

        void measureGaps(Plan plan,Result result,long elapsed,long sampledCount) {
            while(nextGap<plan.checkpoints().size()) {
                Checkpoint target=plan.checkpoints().get(nextGap);
                if(target.dueMs()>elapsed) break;
                double fraction=elapsed==previousGapMs?1:
                    (double)(target.dueMs()-previousGapMs)/(elapsed-previousGapMs);
                double panCount=previousGapCount+fraction*(sampledCount-previousGapCount);
                result.distanceGapsM[nextGap]=target.distanceGapM(panCount,plan.cpr());
                nextGap++;
            }
            previousGapMs=elapsed;
            previousGapCount=sampledCount;
        }
    }

    private static void observe(Plan plan,Result result,Sweep sweep,GoTo.PanSample sample) {
        long elapsed=sample.timeMs()-result.startMs;
        while(sweep.next<plan.checkpoints().size() &&
            plan.direction()*(sweep.actual-plan.checkpoints().get(sweep.next).relativeCount())>=0) {
            result.observed[sweep.next]=new Observation(elapsed,result.wallStartMs+elapsed,sample.count(),sweep.rate);
            sweep.next++;
        }
    }

    private static void reachedLast(Result result) {
        result.reachedLast=true;
        result.reason="Final viewpoint reached";
    }

    private static void skipPassedSpeedUps(Plan plan,Result result,Sweep sweep,SpeedUp[] requested,int from,String reason) {
        for(int i=from;i<sweep.next;i++) if(requested[i].requested())
            skipSpeedUp(result,i,sweep.rate,requested[i].targetRate(plan,i,sweep.rate),reason);
    }

    private static void skipSpeedUp(Result result,int index,double current,double target,String reason) {
        SpeedChange change=new SpeedChange(index,current,target,0);
        change.outcome=reason;
        result.speedChanges[index]=change;
    }

    private static SpeedChange chooseSpeedUp(Plan plan,Result result,Sweep sweep,SpeedUp[] requested,int from,
                                             GoTo.PanTiming timing) throws IOException {
        SpeedChange selected=null;
        for(int i=from;i<sweep.next;i++) {
            if(!requested[i].requested()) continue;
            double target=requested[i].targetRate(plan,i,sweep.rate);
            int period=target>sweep.rate?timing.period(target):sweep.period;
            if(period>=sweep.period)
                skipSpeedUp(result,i,sweep.rate,target,"Current GTi setting already meets or exceeds this rate");
            else if(selected!=null)
                skipSpeedUp(result,i,sweep.rate,target,"Another speed-up selected in the same encoder sample");
            else
                result.speedChanges[i]=selected=new SpeedChange(i,sweep.rate,target,period);
        }
        return selected;
    }

    private static final class PanStop {
        long requestMs=GoTo.now();
        Long ackMs,stoppedMs;
        GoTo.PanSample finalSample;
        String reply="Unconfirmed",issue;

        boolean usable() { return ackMs!=null&&stoppedMs!=null&&finalSample!=null; }
        String problem() { return issue==null?reply:issue; }
    }

    private static PanStop stop(GoTo mount,Runnable tick,GoTo.Trace log) {
        PanStop stop=new PanStop();
        try {
            stop.ackMs=mount.stopPan(at->stop.requestMs=at);
            stop.reply="Acknowledged";
        } catch(IOException e) {
            stop.reply="Unusable reply · "+e.getMessage();
        }
        long deadline=stop.requestMs+STOP_TIMEOUT_MS;
        stop.issue="Timed out waiting for pan to stop";
        while(GoTo.now()<deadline) {
            try {
                if(!mount.panMoving()) {
                    stop.stoppedMs=GoTo.now();
                    stop.issue=null;
                    try { stop.finalSample=mount.readPan(); }
                    catch(IOException e) { stop.issue="Final encoder unavailable · "+e.getMessage(); }
                    break;
                }
            } catch(IOException e) {
                stop.issue="Pan stop status unavailable · "+e.getMessage();
                log.event("hs_stop_status_failed","reason",stop.issue);
            }
            pause(Math.min(deadline,GoTo.now()+SAMPLE_INTERVAL_MS),()->true,tick);
        }
        return stop;
    }

    private static void finishStop(Result result,PanStop stop,GoTo.Trace log) {
        result.stopSentMs=stop.requestMs;
        result.stopAckMs=stop.ackMs;
        result.stopReply=stop.reply;
        result.stoppedMs=stop.stoppedMs;
        result.finalCount=stop.finalSample==null?null:stop.finalSample.count();
        if(stop.issue!=null&&!result.reason.contains(stop.issue)) result.reason+=" · "+stop.issue;
        log.event("hs_stop_result","reason",result.reason,"requestMs",result.stopSentMs,"reply",result.stopReply,
            "replyMs",result.stopAckMs,"stoppedMs",result.stoppedMs,"lastSampleCount",result.lastSampleCount,
            "finalCount",result.finalCount);
    }

    private static void pause(long until,BooleanSupplier active,Runnable tick) {
        while(active.getAsBoolean() && GoTo.now()<until) {
            tick.run();
            long remaining=until-GoTo.now();
            if(remaining<=0) break;
            try { Thread.sleep(Math.min(50,remaining)); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static double expectedCount(Plan plan,int leg,long elapsed) {
        Checkpoint from=plan.checkpoints().get(leg);
        if(leg+1==plan.checkpoints().size()) return from.relativeCount();
        Checkpoint to=plan.checkpoints().get(leg+1);
        double fraction=(double)(elapsed-from.dueMs())/(to.dueMs()-from.dueMs());
        double lat=from.point().lat()+fraction*(to.point().lat()-from.point().lat());
        double lon=Geo.delta(from.point().lon()+fraction*Geo.delta(to.point().lon(),from.point().lon()),0);
        GoTo.Point pivot=plan.reference().pivot();
        double bearing=Geo.bearing(pivot.lat(),pivot.lon(),lat,lon);
        int count=GoTo.panTarget(bearing,plan.reference(),plan.cpr());
        return from.relativeCount()+GoTo.delta(count,from.count());
    }

    private static double trackBearing(GoTo.Point from,GoTo.Point to) {
        return Geo.bearing(from.lat(),from.lon(),to.lat(),to.lon());
    }

    private static double distance(GoTo.Point a,GoTo.Point b) {
        return Geo.distance(a.lat(),a.lon(),b.lat(),b.lon());
    }
}

