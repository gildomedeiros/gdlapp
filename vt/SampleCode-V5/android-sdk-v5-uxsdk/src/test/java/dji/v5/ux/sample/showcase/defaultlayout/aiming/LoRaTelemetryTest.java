package dji.v5.ux.sample.showcase.defaultlayout.aiming;
public final class LoRaTelemetryTest {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static LoRaTelemetry packet(long seq,long ms){return LoRaTelemetry.parse("RX,GPS,"+seq+","+ms+",-28.08159,153.39186,23,0.6,-107,7.7,0");}
    static AimingSession.Inputs input(double accuracy,long fixTime){return new AimingSession.Inputs(new AimingSession.Fix(0.001,0,accuracy,fixTime),0,0,90,10000,null,true);}
    public static void main(String[] args){
        check(packet(1,500).snr==7.7,"Decimal SNR");
        LoRaTelemetry.Tracker t=new LoRaTelemetry.Tracker();
        check(t.accept(packet(100,50000),1000),"First");
        check(!t.accept(packet(100,50000),2000),"Repeat ignored");
        check(!t.accept(packet(99,49500),2100),"Older ignored");
        check(t.accept(packet(104,52000),3000)&&t.gaps==3,"Gap counts without replay");
        check(!t.accept(packet(105,52000),3500),"Frozen sender time ignored");
        check(!t.accept(packet(1,500),4000),"Restart pending");
        check(!t.accept(packet(1,500),4100),"Restart duplicate ignored");
        check(!t.accept(packet(2,1000),4500),"Restart needs third sample");
        check(t.accept(packet(3,1500),5000)&&t.restarts==1,"Confirmed restarted stream");
        LoRaTelemetry.Tracker wrap=new LoRaTelemetry.Tracker();
        wrap.accept(packet(4294967295L,4294967200L),1000);
        check(wrap.accept(packet(0,400),1500),"Unsigned counters wrap");
        for(String bad:new String[]{"GDL_LILYGO_READY","RAW,NOFIX,1,500", "RX,GPS,1,500,NaN,1,8,1,-90,2,0","RX,GPS,1,500,91,1,8,1,-90,2,0","RX,GPS,1,500,1,1,8,1,-90,NaN,0","RX,GPS,1,500,1,1,0,1,-90,2,0"}){
            boolean rejected=false;try{LoRaTelemetry.parse(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Bad input must not produce target");
        }
        for(double accuracy:new double[]{Double.NaN,0,50,Double.POSITIVE_INFINITY})check(input(accuracy,10000).validate(10000)==null,"Accuracy does not gate either source");
        check("stale_gps".equals(input(Double.NaN,6999).validate(10000)),"Freshness still enforced without accuracy");
        check(input(Double.NaN,7000).validate(10000)==null,"Three second boundary unchanged");
        AimingSession.Inputs near=new AimingSession.Inputs(new AimingSession.Fix(0,0,Double.NaN,10000),0,0,90,10000,null,true);
        // CAM3 v2.7: Coincident coordinates are allowed; aiming sends zero instead of pausing.
        check(near.validate(10000)==null,"No minimum-distance gate");
        AimingSession.Inputs invalid=new AimingSession.Inputs(new AimingSession.Fix(Double.NaN,0,Double.NaN,10000),0,0,90,10000,null,true);
        check("gps_quality".equals(invalid.validate(10000)),"Invalid coordinates rejected");
        // Feed accepted radio positions through the actual existing state machine.
        AimingSessionTest.Fake f=new AimingSessionTest.Fake();f.aiming();
        long last=f.time;
        f.time+=3001;f.input=f.data(f.time,last,null,true);f.core.tick();
        check(f.core.state()==AimingSession.State.PAUSED,"LoRa outage pauses existing aiming");
        f.advance(100);check(f.core.state()==AimingSession.State.PAUSED,"Fresh fix does not bypass recovery");
        for(int i=0;i<19;i++)f.advance(100);check(f.core.state()==AimingSession.State.PAUSED,"Two second recovery retained");
        f.advance(100);check(f.core.state()==AimingSession.State.AIMING,"Fresh stable input resumes");
        System.out.println("PASS: TTGO parsing, repeats, gaps, restarts, wrap, accuracy bypass, freshness and pause/recovery");
    }
}
