package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.nio.file.*;
public final class Vt404Test {
    static int checks;
    static final double DEG=ComeToMeTest.DEG;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void near(double a,double b,String why){check(Math.abs(a-b)<.02,why+" actual="+b);}
    static ComeToMeSettings settings(){return new ComeToMeSettings(true,28,50,99,.1,1000,900000,5,90000,-25,-10,15,8,4,300);}
    static WaveLinePositioning preset(double angle,double threshold){return new WaveLinePositioning(Vt40Test.shore(),"beach","diagonal","left",3,threshold,angle,5,2,299);}
    static ComeToMeController planner(double radius,double angle,double desired){
        double n=40-radius*Math.cos(Math.toRadians(angle)),e=radius*Math.sin(Math.toRadians(angle));
        ComeToMeController m=new ComeToMeController();m.start(settings(),1000);m.positioning=preset(desired,18);
        m.update_state_machine(ComeToMeTest.in(1000,n,e,40,0,0),1000,.1);return m;
    }
    static void scenarios(){
        for(double sign:new double[]{-1,1}) {
            ComeToMeController direct=planner(36,sign*45,sign*45);
            check(direct.approaching()&&direct.route.kind.equals("direct"),"same side36 direct");
            near(28,YawAimingMath.distance(direct.approachTargetLat,direct.approachTargetLon,40*DEG,0),"approach radius28");
            check(!planner(30,sign*45,sign*45).approaching(),"same side30 hold");
            ComeToMeController around=planner(36,-sign*45,sign*45);
            check(around.approaching()&&around.route.kind.equals("around")&&around.route.points.length>=3,"opposite side36 around");
            near(28,YawAimingMath.distance(around.approachTargetLat,around.approachTargetLon,40*DEG,0),"around reapproach28");
            around=planner(30,-sign*45,sign*45);
            check(around.approaching()&&around.route.kind.equals("around"),"opposite side30 align");
            near(30,YawAimingMath.distance(around.approachTargetLat,around.approachTargetLon,40*DEG,0),"preserved30 radius");
        }
        ComeToMeController m=planner(25,36.86989765,45);check(m.approaching(),"25m correct angle below filming");
        near(25,YawAimingMath.distance(m.approachTargetLat,m.approachTargetLon,40*DEG,0),"alignment preserves25");
        check(!planner(30,40,45).approaching(),"5degree equality hold");check(planner(30,39.9,45).approaching(),"over5 align");
        check(!planner(33,45,45).approaching(),"33m equality hold");check(planner(33.01,45,45).approaching(),"above33 approach");
        // Beach start, central14m at sea, desired30m shoreward-left is blocked.
        m=new ComeToMeController();m.start(settings(),1000);m.positioning=preset(-45,18);
        m.update_state_machine(ComeToMeTest.in(1000,14,0,0,0,0),1000,.1);
        check(!m.approaching()&&m.pathBlockReason.equals("destination_central_boundary"),"beach destination blocked");
        m=planner(36,-45,45);double lat=m.approachTargetLat,lon=m.approachTargetLon;
        m.update_state_machine(ComeToMeTest.in(1100,m.centralLat/DEG,m.centralLon/DEG,41,1,0),1100,.1);
        near(lat,m.approachTargetLat,"moving surfer frozen endpoint lat");near(lon,m.approachTargetLon,"moving surfer frozen endpoint lon");
        check(m.permits(ComeToMeTest.in(1100,m.centralLat/DEG,m.centralLon/DEG,41,1,0),1100,m.forward,m.right),"exact vector permitted");
        check(!m.permits(ComeToMeTest.in(1100,m.centralLat/DEG,m.centralLon/DEG,41,1,30),1100,m.forward,m.right),"heading changed veto");
        check(!m.permits(ComeToMeTest.in(1100,m.centralLat/DEG-1,m.centralLon/DEG,41,1,0),1100,m.forward,m.right),"beachward final snapshot veto");
        int count=m.route.points.length;long now=1200;
        for(int i=0;i<count;i++){
            double[] point=m.route.points[i];m.update_state_machine(ComeToMeTest.in(now,point[0]/DEG,point[1]/DEG,41,1,0),now,.1);now+=100;
            check(m.forward==0&&m.right==0,"neutral waypoint transition");
        }
        check(!m.approaching()&&m.routeStatus.equals("arrived"),"multi leg complete");
        m=planner(36,-45,45);m.update_state_machine(ComeToMeTest.in(5000,m.centralLat/DEG,m.centralLon/DEG,40,0,0),5000,.1,false);
        check(m.approaching()&&Math.hypot(m.forward,m.right)>0,"old available GPS route continues");
        m.pause(true,5100);check(m.route==null,"manual cancels route");
        m=planner(36,-45,45);m.replaceApproachForRetreat();check(m.route==null&&m.right==0,"retreat clears all legs");
    }
    static void geometry(){
        for(double angle:new double[]{-90,-45,-12.5,0,45,90}){
            WaveLinePositioning p=preset(angle,18);near(1,Math.hypot(p.axisNorth,p.axisEast),"unit axis");
            double[] end=p.destination(ComeToMeTest.in(1000,0,0,40,0,0),28);
            near(28*Math.cos(Math.toRadians(angle)),40-end[0]/DEG,"shoreward offset");near(28*Math.sin(Math.toRadians(angle)),end[1]/DEG,"right offset");
        }
        ComeToMeController m=planner(21,89,1);
        // The initial boundary is too restrictive for E; separately test with central/anchor farther shoreward.
        AimingSession.Inputs in=ComeToMeTest.in(1000,40-21*Math.cos(Math.toRadians(89)),21*Math.sin(Math.toRadians(89)),40,0,0);
        WaveLinePositioning p=preset(1,18);double[] end=p.destination(in,21);
        AngleRoutePlanner.Plan plan=AngleRoutePlanner.plan(p,in,end,0,0,0,0);
        check(plan.reason.equals("none")&&plan.kind.equals("around"),"safe endpoints unsafe chord goes around");
        double a=in.lat,b=in.lon;
        for(double[] point:plan.points){AngleRoutePlanner.Check c=AngleRoutePlanner.check(p,a,b,point[0],point[1],40*DEG,0,0,0,0,0);
            check(c.reason.equals("none")&&c.clearance>=18-1e-6,"whole polygon segment18m clearance (VT405 zero extra buffer)");a=point[0];b=point[1];}
        p=preset(45,0);in=ComeToMeTest.in(1000,14,-25,40,0,0);end=p.destination(in,28);
        plan=AngleRoutePlanner.plan(p,in,end,0,0,0,0);check(plan.reason.equals("none")&&plan.kind.equals("around"),"OFF geometric around route");
        check(p.clearance()==0,"OFF no retreat circle");
        p=preset(45,18);end=p.destination(in,400);plan=AngleRoutePlanner.plan(p,in,end,0,0,0,0);
        check(plan.reason.equals("destination_excursion_limit")||plan.reason.equals("destination_central_boundary"),"unavailable endpoint rejected");
    }
    static void config(String assets)throws Exception{
        String raw=Files.readString(Paths.get(assets,"vt_settings.json"));RetreatSettings r=RetreatSettings.defaults();
        VtSettingsConfig c=VtSettingsConfig.parse(raw,r);near(300,c.movement.maxExcursionMetres,"default300");near(299,c.movement.excursionStop(),"default299 stop");near(5,c.angleTolerance,"default5degrees");
        String old=raw.replaceAll(",?\\s*\"(positioningAngleDegrees|positioningAngleToleranceDegrees|extraPathClearanceMetres|maxExcursionMetres)\"\\s*:\\s*[-0-9.]+","");
        c=VtSettingsConfig.parse(old,r);near(300,c.movement.maxExcursionMetres,"old absent excursion300");near(45,c.angle,"old absent angle45");
        for(String bad:new String[]{"91","-91","null","\"45\""}){
            String changed=raw.replace("\"positioningAngleDegrees\": 45","\"positioningAngleDegrees\": "+bad);
            Vt40Test.fails(()->VtSettingsConfig.parse(changed,r),"angle invalid");checks++;
        }
        c=VtSettingsConfig.parse(raw.replace("\"front\"","\"diagonal\"").replace("\"positioningAngleDegrees\": 45","\"positioningAngleDegrees\": -37.5"),r);near(-37.5,c.angle,"fractional signed angle");
        Vt40Test.fails(()->VtSettingsConfig.parse(raw.replace("\"maxExcursionMetres\": 300","\"maxExcursionMetres\": 9"),r),"excursion invalid");
    }
    static final class Port implements AimingSession.Port {
        final double angle;
        Port(double angle){this.angle=angle;}
        long time=10000;AimingSession.Inputs in=ComeToMeTest.in(time,0,15,40,0,0);
        AimingSession.Authority a=new AimingSession.Authority(AimingSession.Owner.RC,false,false);
        AimingSession.Completion enable;double forward,right,yaw;final AimingSession core=new AimingSession(this);
        public long now(){return time;}public AimingSession.Inputs inputs(){return in;}public AimingSession.Authority authority(){return a;}
        public void enable(AimingSession.Completion c){enable=c;}public void disable(AimingSession.Completion c){c.complete(true);}public void advanced(){}
        public void sendYaw(double v){yaw=v;forward=right=0;}
        public void sendMotion(double y,double f,double r){yaw=y;forward=f;right=r;}
        public RetreatSettings retreatSettings(){return new RetreatSettings(true,18,1000,3,1000);}
        void tick(long delta,double dn,double de,double sn,double se,double heading){time+=delta;in=ComeToMeTest.in(time,dn,de,sn,se,heading);core.tick();}
        void start(){core.startAiming();enable.complete(true);a=new AimingSession.Authority(AimingSession.Owner.MSDK,true,false);core.tick();a=new AimingSession.Authority(AimingSession.Owner.MSDK,true,true);core.tick();tick(100,0,15,40,0,0);}
        public ComeToMeSettings movementSettings(){return settings();}
        public WaveLinePositioning positioningSettings(){return preset(angle,18);}
    }
    static void session(String output)throws Exception {
        for(double angle:new double[]{-45,45}) {
            Port p=new Port(angle);p.start();p.tick(100,0,15,40,0,0);
            check(p.core.movement.approaching()&&Math.hypot(p.forward,p.right)>0,"production session submits angle route");
            check(!p.core.cycleDecision.equals("approach_heading"),"surfer yaw ownership");
            if(angle<0)check(p.core.movement.route.kind.equals("around"),"production other quadrant around");
            double lat=p.core.movement.approachTargetLat;
            AimingSession.Fix saved=p.in.target;
            for(int i=0;i<35;i++){p.time+=100;p.in=new AimingSession.Inputs(saved,0,15*DEG,0,p.time,null,true);p.core.tick();}
            check(p.core.state()==AimingSession.State.AIMING&&Math.hypot(p.forward,p.right)>0,"actual stale available GPS continuation");
            FullSessionLog log=new FullSessionLog(name->Files.newOutputStream(Paths.get(output,angle<0?"vt404-around.jsonl":"vt404-direct.jsonl")),error->{throw new AssertionError(error);});log.enable();
            MovementCycleLog.record(log,p.core,p.time,p.core.cycleId,p.forward,p.right);log.disable("test");
            long deadline=System.currentTimeMillis()+5000;while(log.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(5);
            check(!log.busy(),"route log closed");
            p.time+=100;p.in=new AimingSession.Inputs(null,0,15*DEG,0,p.time,null,true);p.core.tick();
            check(p.core.state()==AimingSession.State.PAUSED&&p.forward==0&&p.right==0,"actual NOFIX pause");
            near(lat,p.core.movement.approachTargetLat,"NOFIX preserves saved destination");
            p.core.stopAiming("test");check(p.core.movement.route==null,"actual stop cancels route");
        }
    }
    public static void main(String[] args)throws Exception {scenarios();geometry();config(args[0]);session(args[1]);System.out.println("PASS: VT4.0.4 "+checks+" checks");}
}
