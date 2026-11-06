package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** VT 4.0.9: production configuration, session control and JSONL regression scenarios. */
public final class Vt409Test {
    static int checks;
    static final double DEG=ComeToMeTest.DEG;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void near(double expected,double actual,String why){check(Math.abs(expected-actual)<.000001,why+" expected="+expected+" actual="+actual);}
    static void fails(Runnable action,String why){try{action.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
    static String settingsRaw;
    static Path assets;
    static String settings(double angle,double offset,boolean enabled){
        JsonObject o=JsonParser.parseString(settingsRaw).getAsJsonObject();
        o.addProperty("positioningAngleDegrees",angle);o.addProperty("yawTargetOffsetDegrees",offset);o.addProperty("enabled",enabled);
        return o.toString();
    }
    static VtSessionConfig load(String raw){
        return VtSessionConfig.load40(name->name.equals("vt_settings.json")?raw:Files.readString(assets.resolve(name)));
    }
    static double signed(double angle,double offset){return load(settings(angle,offset,false)).appliedYawOffset;}
    static void config()throws Exception{
        near(0,VtSettingsConfig.parse(settingsRaw,RetreatSettings.defaults()).yawTargetOffsetDegrees,"bundled zero default");
        JsonObject legacy=JsonParser.parseString(settings(-45,0,false)).getAsJsonObject();legacy.remove("yawTargetOffsetDegrees");
        near(0,load(legacy.toString()).appliedYawOffset,"old external settings still load");
        for(double angle:new double[]{-90,-45,-.1,0,.1,45,90}){
            for(double offset:new double[]{-5,0,5})
                near(offset,signed(angle,offset),"configured sign independent of positioning angle "+angle);
        }
        for(String bad:new String[]{"-181","181","-1e309","1e309","null","\"5\"","true"}){
            String raw=settings(-45,5,false).replace("\"yawTargetOffsetDegrees\":5.0","\"yawTargetOffsetDegrees\":"+bad);
            fails(()->load(raw),"invalid offset rejected: "+bad);
        }
        fails(()->load(settings(-45,5,false).replace("yawTargetOffsetDegrees","yawTargetOffestDegrees")),"misspelling remains rejected");
        near(180,signed(-45,180),"positive endpoint accepted");
        near(-180,signed(45,-180),"negative endpoint accepted");
        JsonObject missingAngle=JsonParser.parseString(settings(0,-5,false)).getAsJsonObject();
        missingAngle.remove("positioningAngleDegrees");
        near(-5,load(missingAngle.toString()).appliedYawOffset,"offset independent of omitted positioning setting");
        VtSessionConfig old=load(settings(-45,5,false));
        near(-5,load(settings(45,-5,false)).appliedYawOffset,"next session adopts configured negative offset");
        near(5,old.appliedYawOffset,"existing snapshot immutable");

        JsonObject enabled=JsonParser.parseString(settings(-45,5,true)).getAsJsonObject();enabled.addProperty("waveLineId","beach");
        List<WaveLineLibrary.Profile> profiles=new ArrayList<>();
        profiles.add(new WaveLineLibrary.Profile("beach","Beach",new WaveLineGeometry(0,-50*DEG,0,50*DEG,"leftOfAToB"),"manual","2026-11-06",0,0,0,0));
        String library=new WaveLineLibrary(profiles).json();
        VtSessionConfig withMovement=VtSessionConfig.load40(name->name.equals("vt_settings.json")?enabled.toString():name.equals("vt_wave_lines.json")?library:Files.readString(assets.resolve(name)));
        near(5,withMovement.appliedYawOffset,"offset loads with movement enabled");
        near(-45,withMovement.positioning.angleDegrees,"positioning angle itself unchanged");
        near(0,VtSessionConfig.load(name->Files.readString(assets.resolve(name))).appliedYawOffset,"legacy three-file loader remains zero");
    }

    static final class Port implements AimingSession.Port {
        long time=10000;
        AimingSession.Authority owner=new AimingSession.Authority(AimingSession.Owner.RC,false,false);
        AimingSession.Completion enable;
        AimingSession.Inputs in;
        final AimingSession core=new AimingSession(this);
        final ComeToMeSettings movement;
        double yaw,forward,right;
        int sends;
        Port(double angle,double offset){
            VtSessionConfig c=load(settings(angle,offset,false));
            movement=c.movement;core.configuredYawOffset=c.appliedYawOffset;core.rotationCurve=c.rotation;
            in=ComeToMeTest.in(time,0,0,100,0,0);
        }
        public long now(){return time;}
        public AimingSession.Inputs inputs(){return in;}
        public AimingSession.Authority authority(){return owner;}
        public void enable(AimingSession.Completion c){enable=c;}
        public void advanced(){}
        public void disable(AimingSession.Completion c){c.complete(true);}
        public ComeToMeSettings movementSettings(){return movement;}
        public void sendYaw(double y){yaw=y;forward=right=0;sends++;}
        public void sendMotion(double y,double f,double r){yaw=y;forward=f;right=r;sends++;}
        void start(){
            core.startAiming();enable.complete(true);owner=new AimingSession.Authority(AimingSession.Owner.MSDK,true,false);core.tick();
            owner=new AimingSession.Authority(AimingSession.Owner.MSDK,true,true);core.tick();tick(100,0,0,null);
            check(core.state()==AimingSession.State.AIMING,"confirmed session active");
        }
        void tick(double north,double east,double heading,String problem){
            time+=100;
            in=new AimingSession.Inputs(new AimingSession.Fix(north*DEG,east*DEG,0,time,123),0,0,heading,time,problem,true);
            core.tick();
        }
    }
    static void aiming(){
        for(double angle:new double[]{-45,45,0})for(double expected:new double[]{-5,0,5}){
            Port p=new Port(angle,expected);p.start();
            near(expected,p.core.appliedYawOffset,"session applies configured sign");
            near(expected<0?355:expected,p.core.targetAimingHeadingDeg,"adjusted heading normalized");
            near(expected,p.core.cycleAngle,"controller error to adjusted target");
            check(expected==0?p.yaw==0:Math.signum(p.yaw)==Math.signum(expected),"sent yaw follows shifted target");
            near(0,p.forward,"aiming-only session no forward command");near(0,p.right,"aiming-only session no lateral command");
        }
        Port p=new Port(-45,5);p.start();
        for(double h:new double[]{2,5,8}){p.tick(100,0,h,null);near(0,p.yaw,"normal tolerance around +5 at "+h);}
        p=new Port(45,-5);p.start();
        for(double h:new double[]{352,355,358}){p.tick(100,0,h,null);near(0,p.yaw,"normal tolerance around -5 at "+h);}
        for(double offset:new double[]{5,-5}){
            p=new Port(0,offset);p.start();
            double east=offset>0?-Math.tan(Math.toRadians(1))*100:Math.tan(Math.toRadians(1))*100;
            p.tick(100,east,offset>0?359:1,null);
            near(offset>0?4:356,p.core.targetAimingHeadingDeg,"target wraps through north");
            near(offset,p.core.cycleAngle,"shortest route across north");
        }
        // Compare zero-offset session outputs against the existing curve over a changing heading trace.
        p=new Port(-45,0);p.start();
        SurferYawController reference=new SurferYawController();double previous=0;
        reference.calculate(0,0,.1,false,p.time,p.movement.maxYawRate,p.movement.yawAcceleration,p.core.rotationCurve);
        for(double h:new double[]{350,345,340,350,358,1,4,8,15,0}){
            p.tick(100,0,h,null);
            previous=reference.calculate(YawAimingMath.shortestHeadingError(0,h),previous,.1,false,p.time,p.movement.maxYawRate,p.movement.yawAcceleration,p.core.rotationCurve);
            near(previous,p.yaw,"zero offset exact legacy command trace");
        }
        p=new Port(-45,5);p.start();
        RotationSpeedCurve custom=new RotationSpeedCurve(new double[][]{{0,0},{3,0},{5,3},{10,3.5},{20,8.5},{33,15}},new double[][]{{0,0},{3,1.5},{5,5},{10,10},{20,20},{30,30}});
        p.core.rotationCurve=custom;p.tick(100,0,0,null);
        near(3,p.core.surferYaw.desiredRate,"normal JSON curve uses adjusted five-degree error");
        double previousYaw=p.yaw;
        p.core.movement.ride.riding=true;p.core.movement.ride.expiresAt=p.time+90000;p.core.movement.riding=true;
        p.tick(100,0,0,null);
        near(5,p.core.surferYaw.desiredRate,"riding JSON curve uses adjusted five-degree error");
        check(Math.abs(p.yaw-previousYaw)<=.800001,"acceleration limit preserved");
        p.tick(100,0,4,null);near(.5,p.core.surferYaw.desiredRate,"riding retains sub-three-degree correction");
        p.tick(100,0,10,null);check(p.core.surferYaw.blocked,"reversal block applies to shifted target");near(0,p.yaw,"blocked reversal sends zero");
        p=new Port(-45,5);p.start();p.tick(100,0,0,"pilot_stick");
        check(p.core.state()==AimingSession.State.PAUSED,"pilot input pauses offset aiming");near(0,p.core.appliedYawOffset,"pause does not retain old offset");
        check(Double.isNaN(p.core.targetAimingHeadingDeg),"pause does not retain old target");
        p=new Port(-45,5);p.start();p.core.stopAiming("user_stop");p.tick(100,0,0,null);
        check(p.core.state()!=AimingSession.State.AIMING,"Stop still stops");near(0,p.core.appliedYawOffset,"stopped cycle clears offset");
        check(Double.isNaN(p.core.targetAimingHeadingDeg),"stopped cycle clears target");
    }
    static Vt407Test.Port navigation(){
        Vt407Test.Port original=new Vt407Test.Port(),biased=new Vt407Test.Port();
        biased.core.configuredYawOffset=5;original.start();biased.start();
        near(5,biased.core.appliedYawOffset,"preset holding tracks offset surfer target");
        for(int i=0;i<650&&!biased.core.movement.returning();i++){
            original.tick(40,20,100,0,0);biased.tick(40,20,100,0,0);
        }
        check(original.core.movement.returning()&&biased.core.movement.returning(),"return state preserved");
        near(original.core.movement.returnHeading,biased.core.movement.returnHeading,"saved return heading preserved");
        double heading=biased.core.movement.returnHeading;
        original.tick(40,22,100,0,heading);biased.tick(40,22,100,0,heading);
        near(original.yaw,biased.yaw,"aligned navigation yaw unchanged");
        near(original.forward,biased.forward,"navigation forward unchanged");near(original.right,biased.right,"navigation lateral unchanged");
        near(0,biased.core.appliedYawOffset,"navigation applies no framing offset");check(Double.isNaN(biased.core.targetAimingHeadingDeg),"navigation has no surfer target");
        original.tick(40,22,100,0,heading+10);biased.tick(40,22,100,0,heading+10);
        near(original.yaw,biased.yaw,"navigation correction unchanged");
        near(YawAimingMath.shortestHeadingError(heading,heading+10),biased.core.cycleAngle,"navigation error uses saved heading");

        Vt407Test.Port retreat=new Vt407Test.Port();retreat.core.configuredYawOffset=5;retreat.start();retreat.tick(40,0,50,0,0);
        check(retreat.core.retreat.active,"retreat remains active at its original distance");
        near(-3,retreat.forward,"retreat speed unchanged");near(0,retreat.right,"retreat lateral unchanged");near(5,retreat.core.appliedYawOffset,"surfer yaw offset continues during retreat");
        Vt407Test.Port preset=new Vt407Test.Port();preset.core.configuredYawOffset=5;preset.start();preset.tick(0,0,100,0,0);
        check(preset.core.movement.approaching()&&preset.core.movement.presetApproach(),"preset approach remains active");
        near(5,preset.core.appliedYawOffset,"preset approach retains surfer framing");

        AimingSessionTest.Fake legacy=new AimingSessionTest.Fake();
        legacy.movement=new ComeToMeSettings(true,70,50,99,.1,1000,900000);legacy.core.configuredYawOffset=5;legacy.aiming();legacy.advance(100);
        check(legacy.core.movement.approaching()&&!legacy.core.movement.presetApproach(),"legacy approach alignment exercised");
        near(0,legacy.core.appliedYawOffset,"legacy navigation alignment no offset");
        check(Double.isNaN(legacy.core.targetAimingHeadingDeg),"legacy navigation no surfer target");
        return biased;
    }
    static void logs(String output,Vt407Test.Port navigation)throws Exception{
        Path path=Paths.get(output,"vt409-log.jsonl");
        FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(path),error->{throw new AssertionError(error);},"4.0.9-test");log.enable();
        Port right=new Port(-45,5);right.start();
        // Deliberately different observed input must not replace the actual decision snapshot.
        AimingCycleLog.record(log,right.core,ComeToMeTest.in(right.time,0,0,0,100,90),right.time,false,right.core.cycleId,right.yaw);
        Port left=new Port(-45,-5);left.start();AimingCycleLog.record(log,left.core,left.in,left.time,false,left.core.cycleId,left.yaw);
        right.tick(0,0,0,null);AimingCycleLog.record(log,right.core,right.in,right.time,false,right.core.cycleId,right.yaw);
        right.tick(100,0,0,"pilot_stick");AimingCycleLog.record(log,right.core,right.in,right.time,false,-1,0);
        AimingCycleLog.record(log,navigation.core,navigation.in,navigation.time,false,navigation.core.cycleId,navigation.yaw);
        long deadline=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);
        log.disable("test");while(log.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);check(!log.busy(),"log flushed");
        int count=0;
        for(String row:Files.readAllLines(path)){
            JsonObject o=JsonParser.parseString(row).getAsJsonObject();
            if(!o.get("event").getAsString().equals("aiming_cycle"))continue;
            check(o.has("appliedYawOffset")&&o.has("targetAimingHeadingDeg"),"every cycle includes new fields");
            if(count==0){near(0,o.get("subjectBearingDeg").getAsDouble(),"raw bearing remains raw");near(5,o.get("remainingAngleDeg").getAsDouble(),"logged error is shifted");check(o.get("submitted").getAsBoolean(),"submission recorded");}
            if(count==2||count==3||count==4){near(0,o.get("appliedYawOffset").getAsDouble(),"no stale offset");check(o.get("targetAimingHeadingDeg").isJsonNull(),"no stale target");}
            count++;
        }
        check(count==5,"all log scenarios recorded");
    }
    public static void main(String[] args)throws Exception{
        assets=Paths.get(args[0]);settingsRaw=Files.readString(assets.resolve("vt_settings.json"));
        config();aiming();Vt407Test.Port navigation=navigation();logs(args[1],navigation);
        System.out.println("PASS: "+checks+" VT 4.0.9 configuration, framing, curves, navigation, retreat and log checks");
    }
}
