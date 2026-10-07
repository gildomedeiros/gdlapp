package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
import java.util.*;
/** Production geometry, planner, session, JSON and stationary capture; no aircraft. */
public final class Vt40Test {
    static int checks;
    static final double DEG=ComeToMeTest.DEG;
    static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    static void near(double expected,double actual,String why){check(Math.abs(expected-actual)<.015,why+" actual="+actual);}
    static WaveLineGeometry shore(){return new WaveLineGeometry(0,0,0,100*DEG,"leftOfAToB");}
    static WaveLinePositioning preset(String mode,String side){return new WaveLinePositioning(shore(),"beach",mode,side,3,18);}
    static ComeToMeSettings settings(){return new ComeToMeSettings(true,23,100,99,.1,1000,900000,5,90000,-25,-10,15,8,4);}
    static ComeToMeController planner(String mode,String side,double dn,double de,double sn,double se){
        ComeToMeController m=new ComeToMeController();m.start(settings(),1000);m.positioning=preset(mode,side);
        m.update_state_machine(ComeToMeTest.in(1000,dn,de,sn,se,0),1000,.1);return m;
    }
    static void geometry(){
        WaveLineGeometry g=shore();near(1,g.seaNorth,"east A/B left sea north");near(0,g.seaEast,"sea perpendicular");
        WaveLineGeometry reverse=new WaveLineGeometry(0,100*DEG,0,0,"rightOfAToB");near(g.seaNorth,reverse.seaNorth,"reversed A/B + side preserves sea");
        double[] m=preset("front","left").measures(ComeToMeTest.in(1000,0,15,20,0,0));near(20,m[0],"front projected shoreward");near(-15,m[1],"front other axis alongshore");
        m=preset("sideways","left").measures(ComeToMeTest.in(1000,15,-20,0,0,0));near(20,m[0],"left looking sea west");near(15,m[1],"sideways other-axis shore distance");
        m=preset("sideways","right").measures(ComeToMeTest.in(1000,15,20,0,0,0));near(20,m[0],"right looking sea east");
        near(0,WaveLineGeometry.segmentDistance(-30,0,30,0),"crossing route clearance zero");
        check(!preset("front","left").safePath(ComeToMeTest.in(1000,0,0,20,0,0),25*DEG,0,0,0),"route through surfer rejected");
    }
    static void fixedPlans(){
        for(double east:new double[]{-50,0,50}) {
            ComeToMeController m=planner("front","left",0,east+15,40,east);
            check(m.approaching(),"G1/G2/G3 plan");near(17,m.approachTargetLat/DEG,"23m shoreward fixed target");near(east,m.approachTargetLon/DEG,"alongshore aligned endpoint");
            double lat=m.approachTargetLat,lon=m.approachTargetLon;
            m.update_state_machine(ComeToMeTest.in(1100,0,east+15,40,east,0),1100,.1);
            check(m.forward>0&&m.right<0,"combined forward/left movement while aiming north");check(Math.hypot(m.forward,m.right)<=4.000001,"combined speed capped");
            check(m.permits(ComeToMeTest.in(1100,0,east+15,40,east,0),1100,m.forward,m.right),"last snapshot permits exact body vector");
            check(!m.permits(ComeToMeTest.in(1100,0,east+15,40,east,30),1100,m.forward,m.right),"changed heading vetoes obsolete body vector");
            m.update_state_machine(ComeToMeTest.in(6100,0,east+15,50,east+10,0),6100,.1);near(lat,m.approachTargetLat,"moving surfer does not retarget");near(lon,m.approachTargetLon,"moving surfer does not retarget sideways");
            check(m.destinationFixTime==1000,"destination fix time remains planning fix");
            m.update_state_machine(ComeToMeTest.in(6200,17,east,50,east+10,0),6200,.1);check(!m.approaching()&&m.forward==0&&m.right==0,"fixed endpoint arrival stops both axes");
        }
        ComeToMeController m=planner("front","left",0,15,20,0);check(m.approaching(),"alignment only journey below filming separation");near(0,m.approachTargetLat,"alignment preserves 20m projection");
        check(m.journeyKind.equals("alignment_only_preserve_projection"),"alignment journey explicitly named");
        m=planner("front","left",0,0,20,0);check(!m.approaching(),"20 projected /20 direct /zero alongshore error holds");
        m=planner("front","left",0,15,15,0);check(!m.approaching()&&m.reason.contains("blocked"),"15 projection alignment endpoint inside retreat circle rejected");
        m=planner("front","left",40,0,20,0);check(!m.approaching()&&m.reason.equals("wrong_filming_side_hold"),"surfer shoreward of drone does not cross surfer");
        for(String side:new String[]{"left","right"}) {
            int sign=side.equals("left")?-1:1;m=planner("sideways",side,15,sign*40,0,0);
            near(0,m.approachTargetLat/DEG,"sideways equal shore distance endpoint");near(sign*23,m.approachTargetLon/DEG,"sideways chosen23m endpoint");
        }
        m=planner("front","left",0,0,300,0);check(!m.approaching()&&m.reason.contains("blocked"),"endpoint beyond249m excursion blocked");
        m=planner("front","left",0,15,40,0);m.update_state_machine(ComeToMeTest.in(5000,0,15,40,0,0),5000,.1,false);
        check(m.approaching()&&Math.hypot(m.forward,m.right)>0,"saved journey continues with old surfer fix");
        m.replaceApproachForRetreat();check(m.right==0&&m.forward==0&&!m.approaching(),"retreat clears both axes and destination");
        m=new ComeToMeController();m.start(new ComeToMeSettings(true,23,100,18,.1,1000,900000,5),1000);m.positioning=preset("front","left");
        m.update_state_machine(ComeToMeTest.in(1000,0,15,40,0,0),1000,.1);
        m.update_state_machine(ComeToMeTest.in(2000,0,15,50,0,0),2000,.1);check(m.riding&&!m.approaching()&&m.right==0,"fresh ride interrupts preset journey");
    }
    static final class Port implements AimingSession.Port {
        long time=10000;AimingSession.Inputs in=ComeToMeTest.in(time,0,15,40,0,0);
        AimingSession.Authority a=new AimingSession.Authority(AimingSession.Owner.RC,false,false);
        AimingSession.Completion enable;double forward,right,yaw;final AimingSession core=new AimingSession(this);
        public long now(){return time;}public AimingSession.Inputs inputs(){return in;}public AimingSession.Authority authority(){return a;}
        public void enable(AimingSession.Completion c){enable=c;}public void disable(AimingSession.Completion c){c.complete(true);}public void advanced(){}
        public void sendYaw(double v){yaw=v;forward=right=0;}
        public void sendMotion(double y,double f,double r){yaw=y;forward=f;right=r;}
        public ComeToMeSettings movementSettings(){return settings();}public WaveLinePositioning positioningSettings(){return preset("front","left");}
        public RetreatSettings retreatSettings(){return new RetreatSettings(true,18,1000,3,1000);}
        void tick(long delta,double dn,double de,double sn,double se,double heading){time+=delta;in=ComeToMeTest.in(time,dn,de,sn,se,heading);core.tick();}
        void start(){core.startAiming();enable.complete(true);a=new AimingSession.Authority(AimingSession.Owner.MSDK,true,false);core.tick();a=new AimingSession.Authority(AimingSession.Owner.MSDK,true,true);core.tick();tick(100,0,15,40,0,0);}
    }
    static void session(String output)throws Exception {
        Port p=new Port();p.start();p.tick(100,0,15,40,0,0);check(p.right<0&&p.forward>0,"actual session submits combined movement");
        check(!p.core.cycleDecision.equals("approach_heading"),"surfer owns yaw during preset approach");
        check(p.yaw<0,"yaw faces surfer independently of movement destination");
        FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(Paths.get(output,"vt40-test.jsonl")),error->{throw new AssertionError(error);});log.enable();
        MovementCycleLog.record(log,p.core,p.time,p.core.cycleId,p.forward,p.right);log.disable("test");
        long deadline=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);
        check(!log.busy(),"VT40 writer closed");
        p.tick(100,25,0,40,0,0);check(p.core.retreat.active&&p.right==0&&p.forward==-3,"direct15m overrides approach, lateralzero");
        check(!p.core.movement.approaching(),"retreat cancels fixed journey");
        for(int i=0;i<11;i++)p.tick(100,0,15,40,0,0);check(p.forward==0&&p.right==0,"cooldown blocks translation");
        for(int i=0;i<11;i++)p.tick(100,0,15,40,0,0);check(p.core.movement.approaching(),"fresh fix replans after cooldown");
        AimingSession.Fix retained=p.in.target;
        for(int i=0;i<35;i++){p.time+=100;p.in=new AimingSession.Inputs(retained,0,15*DEG,0,p.time,null,true);p.core.tick();}
        check(p.core.state()==AimingSession.State.AIMING&&p.right<0,"actual session continues saved journey with old but available GPS fix");
        double destination=p.core.movement.approachTargetLat;
        p.time+=100;p.in=new AimingSession.Inputs(null,0,15*DEG,0,p.time,null,true);p.core.tick();
        check(p.core.state()==AimingSession.State.PAUSED&&p.right==0&&p.forward==0,"explicit NOFIX retains existing protection pause outside retreat/cooldown");
        near(destination,p.core.movement.approachTargetLat,"NOFIX pause preserves fixed destination");
        p.core.stopAiming("test");check(p.core.movement.right==0,"stop neutralizes lateral");
    }
    static void capture(){
        WaveLineCapture c=new WaveLineCapture(1000);WaveLineCapture.Point p=null;
        for(int i=0;i<10;i++)p=c.observe(new AimingSession.Fix(0,0,0,1000+i*500),1000+i*500);
        check(p!=null&&p.fixes==10,"ten stationary fresh distinct fixes capture");
        c=new WaveLineCapture(1000);for(int i=0;i<20;i++)check(c.observe(new AimingSession.Fix(0,0,0,1000),1000+i*50)==null,"duplicate fix cannot capture");
        c=new WaveLineCapture(1000);for(int i=0;i<9;i++)c.observe(new AimingSession.Fix(0,0,0,1000+i*500),1000+i*500);
        check(c.observe(null,5500)==null,"NOFIX resets pending batch");p=c.observe(new AimingSession.Fix(0,0,0,6000),6000);check(p==null,"batch restarts after NOFIX");
        c=new WaveLineCapture(1000);for(int i=0;i<10;i++)p=c.observe(new AimingSession.Fix(i%2*20*DEG,0,0,1000+i*500),1000+i*500);check(p==null&&c.status.contains("scatter"),"scattered positions rejected");
        try{c.observe(null,121000);throw new AssertionError("timeout missing");}catch(IllegalArgumentException expected){checks++;}
    }
    static void fails(Runnable r,String why){try{r.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
    static void config(String assets)throws Exception {
        Path dir=Paths.get(assets);String settings=Files.readString(dir.resolve("vt_settings.json"));
        RetreatSettings retreat=RetreatSettings.defaults();VtSettingsConfig c=VtSettingsConfig.parse(settings,retreat);near(28,c.movement.filmingDistance,"new default respects23+5");
        fails(()->VtSettingsConfig.parse(settings.replace("28","27"),retreat),"filming below retreat+5 rejected");
        fails(()->VtSettingsConfig.parse(settings.replace("\"front\"","\"other\""),retreat),"invalid mode rejected");
        fails(()->VtSettingsConfig.parse(settings.replace("\"enabled\": true","\"enabled\": \"true\""),retreat),"boolean string rejected");
        fails(()->VtSettingsConfig.parse(settings.replace("\"version\": 1","\"version\":1,\"version\":1"),retreat),"duplicate JSON key rejected");
        List<WaveLineLibrary.Profile> list=new ArrayList<>();list.add(new WaveLineLibrary.Profile("beach","Beach",shore(),"manual","2026-10-03",0,0,0,0));
        String library=new WaveLineLibrary(list).json();check(WaveLineLibrary.parse(library).find("beach").geometry.seaSide.equals("leftOfAToB"),"saved profile roundtrip");
        fails(()->WaveLineLibrary.parse(library.replace("\"latitude\": 0.0","\"latitude\": 90.0")),"bad coordinates rejected");
        String chosen=settings.replace("\"waveLineId\": \"\"","\"waveLineId\": \"beach\"");Map<String,String> files=new HashMap<>();
        for(String n:new String[]{"vt_retreat_settings.json","vt_rotation_speeds.json","vt_gimbal_bands.json"})files.put(n,Files.readString(dir.resolve(n)));
        files.put("vt_settings.json",chosen);files.put("vt_wave_lines.json",library);
        files.put("vt_settings.json",settings);
        try{VtSessionConfig.load40(files::get);throw new AssertionError("blank selection accepted");}catch(VtSessionConfig.WaveLineSetupRequired expected){check(expected.getMessage().contains("No wave line selected"),"blank selection gives setup action");}
        files.put("vt_wave_lines.json","{\"version\":1,\"waveLines\":[]}");
        try{VtSessionConfig.load40(files::get);throw new AssertionError("empty library accepted");}catch(VtSessionConfig.WaveLineSetupRequired expected){check(expected.getMessage().contains("No saved wave lines"),"empty library gives capture action");}
        files.put("vt_settings.json",chosen);files.put("vt_wave_lines.json",library);
        VtSessionConfig snapshot=VtSessionConfig.load40(files::get);check(snapshot.positioning.mode.equals("front"),"all five configs validate preset");
        files.put("vt_settings.json",chosen.replace("\"front\"","\"sideways\""));check(snapshot.positioning.mode.equals("front"),"loaded session immutable after file edit");
        check(VtSessionConfig.load40(files::get).positioning.mode.equals("sideways"),"next load applies sideways");
        files.remove("vt_wave_lines.json");fails(()->VtSessionConfig.load40(files::get),"missing library blocks Start");
    }
    static void fullLogReopen(){
        FullLogOpenPolicy p=new FullLogOpenPolicy();check(p.wanted,"full logging requested by default");
        p.foregroundEntered();check(!p.shouldOpen(true,true,false,true),"busy close postpones reopen");
        check(p.shouldOpen(true,true,false,false),"reopen after asynchronous close completes");
        check(!p.shouldOpen(true,true,false,false),"opening failure does not loop alerts");
        p.request(false);p.foregroundEntered();check(!p.shouldOpen(true,true,false,false),"explicit off respected");
        p.request(true);check(!p.shouldOpen(true,false,false,false),"permission required");
        check(p.shouldOpen(true,true,false,false),"grant opens requested log");
        p.foregroundEntered();check(!p.shouldOpen(false,true,false,false),"background does not open");
        check(p.shouldOpen(true,true,false,false),"next foreground opens");
    }
    static void retreatBoundary() {
        for(String mode:new String[]{"front","sideways"}) {
            ComeToMeController m=planner(mode,"left",0,0,40,0);
            RetreatController r=new RetreatController((e,w)->{});r.start(new RetreatSettings(true,18,2000,4,5000));
            r.active=true;r.deadline=10000;
            near(-4,r.command(m,ComeToMeTest.in(2000,10,0,20,0,0)),"full retreat outside boundary "+mode);
            near(-.5,r.command(m,ComeToMeTest.in(2000,1,0,10,0,0)),"shoreward braking near boundary "+mode);
            check(!r.permits(m,ComeToMeTest.in(2000,1,0,10,0,0),2000,-4),"final snapshot vetoes full inward speed");
            near(0,r.command(m,ComeToMeTest.in(2000,0,0,10,0,0)),"at boundary blocked");
            check(r.boundaryBlocked,"blocked flag");
            near(-4,r.command(m,ComeToMeTest.in(2000,0,0,-10,0,180)),"surfer shoreward permits seaward retreat");
            near(-4,r.command(m,ComeToMeTest.in(2000,-2,0,-10,0,180)),"already shoreward permits recovery");
            near(0,r.command(m,ComeToMeTest.in(2000,-2,0,10,0,0)),"already shoreward blocks further inward retreat");
            near(-4,r.command(m,ComeToMeTest.in(2000,0,0,0,10,90)),"parallel retreat allowed");
            m.pause(true,2100);m.update_state_machine(ComeToMeTest.in(2200,-20,0,40,0,0),2200,.1);
            near(0,m.initialCentralLat,"manual reposition preserves original boundary");near(-20,m.centralLat/DEG,"return central recaptured");
            m.start(settings(),2300);m.positioning=preset(mode,"left");m.update_state_machine(ComeToMeTest.in(2400,10,0,40,0,0),2400,.1);
            near(10,m.initialCentralLat/DEG,"new Start resets boundary");
        }
        ComeToMeController m=planner("front","left",40,0,20,0);
        check(m.filmingSideStatus().equals("wrong_side")&&m.pathCheckStatus.equals("not_checked"),"wrong side distinct from path checks");
        m=planner("front","left",0,15,15,0);
        check(m.filmingSideStatus().equals("correct_side")&&m.pathBlockReason.equals("retreat_clearance"),"retreat clearance logged distinctly");
        m=planner("front","left",0,0,300,0);check(m.pathBlockReason.equals("excursion_limit"),"excursion reason distinct");
        Port p=new Port();p.start();p.tick(100,0,15,10,15,0);
        check(p.core.retreat.active && p.forward==0 && p.right==0,"real session blocks boundary retreat");
        check(p.core.cycleDecision.equals("aligned_zero")||p.core.cycleDecision.equals("surfer_correction"),"surfer yaw remains owner when blocked");
        p.tick(100,0,15,-10,15,180);check(p.forward==-3,"real session permits seaward retreat after crossing");
    }
    public static void main(String[] args)throws Exception {retreatBoundary();fullLogReopen();geometry();fixedPlans();capture();config(args[0]);session(args[1]);System.out.println("PASS: VT 4.0 "+checks+" checks");}
}
