package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** CAM3 v2.7: Behavioral scenarios for votes, commitments, long routes and existing flight guards. */
public final class GoalkeeperTest {
    private static int count;
    private static void check(boolean value,String why) { count++; if(!value) throw new AssertionError(why); }
    private static void near(double expected,double actual,String why) { check(Math.abs(expected-actual)<1e-6,why+" actual="+actual); }
    private static AimingSession.Inputs at(double bearing,double distance,long time,double heading) {
        double angular=distance/6371000.0, angle=Math.toRadians(bearing);
        double lat=Math.asin(Math.sin(angular)*Math.cos(angle));
        double lon=Math.atan2(Math.sin(angle)*Math.sin(angular),Math.cos(angular));
        return new AimingSession.Inputs(new AimingSession.Fix(Math.toDegrees(lat),Math.toDegrees(lon),Double.NaN,time,100+time),0,0,heading,time,null,true);
    }
    public static void main(String[] args) {
        DominantDirectionTracker votes=new DominantDirectionTracker();
        int[] signs={1,1,1,1,1,-1,1,-1,-1,-1,-1,-1};
        int[] right={1,2,3,4,5,5,5,4,3,2,1,1};
        int[] left ={0,0,0,0,0,1,1,2,3,4,5,5};
        double angle=40;
        votes.observe(at(angle,20,10000,0),10000);
        check(votes.calculateDominantDirection()==1 && votes.defaultRight(),"no votes defaults right");
        for(int i=0;i<signs.length;i++) {
            long time=10500+i*500; angle+=signs[i]*5;
            AimingSession.Inputs in=at(angle,20,time,180);
            votes.observe(in,time);
            check(votes.rightVotes()==right[i] && votes.leftVotes()==left[i],"three-second table row "+i);
            check(votes.calculateDominantDirection()==(left[i]>=3 && left[i]>right[i] ? -1 : 1),"majority "+i);
            for(int j=1;j<5;j++) votes.observe(in,time+j*100);
            check(votes.rightVotes()==right[i] && votes.leftVotes()==left[i],"duplicate cycles not votes "+i);
        }
        votes.observe(at(angle,20,16000,0),19000);
        check(votes.rightVotes()==0 && votes.leftVotes()==0,"votes expire at exactly three seconds");
        votes.reset(); votes.observe(at(359,20,20000,90),20000); votes.observe(at(1,20,20500,-90),20500);
        check(votes.rightVotes()==1,"north wrap clockwise, independent of heading");
        votes.observe(at(359,20,21000,0),21000); check(votes.leftVotes()==1,"north wrap anticlockwise");
        votes.observe(at(359,20,21500,180),21500); check(votes.lastVote.equals("neutral"),"stationary subject not heading vote");
        AimingSession.Fix fixed=at(359,20,22000,0).target;
        votes.observe(new AimingSession.Inputs(fixed,.00001,0,0,22000,null,true),22000);
        check(votes.lastVote.equals("neutral"),"aircraft translation alone not a surfer vote");
        votes.observe(at(30,20,100,0),100); check(votes.rightVotes()==0 && votes.leftVotes()==0,"time rewind clears history");
        votes.observe(at(30,20,5000,0),5000); check(votes.lastVote.equals("baseline"),"gap does not bridge motion");

        NearbyDirectionLock lock=new NearbyDirectionLock();
        check(lock.update(true,true,60,-1,1000)==null && lock.direction()==0,"60m not near");
        check(lock.update(true,true,59.99,-1,1500).startsWith("captured"),"entry captures left");
        for(int i=0;i<10;i++) { lock.update(true,true,4,1,2000+i*100); check(lock.direction()==-1,"opposing votes cannot change lock"); }
        check(lock.update(true,true,20,1,301499)==null,"not refreshed early");
        check(lock.update(true,true,20,1,301500).startsWith("refreshed") && lock.direction()==1,"five minutes recaptures right");
        check(lock.age(301500)==0,"refresh restarts timer");
        lock.update(true,false,Double.NaN,-1,602000); check(lock.direction()==1,"stale pause retains commitment, no refresh from bad data");
        lock.update(true,true,20,-1,602001); check(lock.direction()==-1,"refresh on recovered valid aiming");
        check(lock.update(true,false,60,1,602002).contains("outside_60m"),"range exit releases even paused");
        lock.update(true,false,20,1,602003); check(lock.direction()==0,"pause cannot capture");
        lock.update(true,true,20,-1,602004); lock.update(false,true,20,1,602005); check(lock.direction()==0,"toggle off clears");
        near(1,NearbyDirectionLock.multiplier(60),"far multiplier"); near(1.25,NearbyDirectionLock.multiplier(45),"mid multiplier");
        near(1.5,NearbyDirectionLock.multiplier(4),"below5 gets capped gain");

        near(-330,YawAimingMath.directedError(30,0,-1),"left lock takes long way");
        near(330,YawAimingMath.directedError(330,0,1),"right lock takes long way");
        near(-30,YawAimingMath.directedError(330,0,0),"unlocked shortest route");
        near(-2,YawAimingMath.directedError(358,0,1),"alignment checked before long route");
        near(0,YawAimingMath.calculateNearbyYawRate(-2,12,.1,1.5),"deadband stops");
        near(-.6,YawAimingMath.calculateNearbyYawRate(-330,0,.1,1.5),"acceleration 6");
        near(-12,YawAimingMath.calculateNearbyYawRate(-330,-12,.1,1.5),"nearby cap");
        near(-11.6,YawAimingMath.calculateNearbyYawRate(-330,-12,.1,1),"smooth rate reduction on exit");
        near(0,YawAimingMath.calculateNearbyYawRate(330,-12,.1,1.5),"new opposite lock brakes once");
        for(double error:new double[]{-179,-30,-3,0,3,30,179}) for(double previous:new double[]{-8,-2,0,2,8})
            near(YawAimingMath.calculateYawRate(error,previous,.1),YawAimingMath.calculateNearbyYawRate(error,previous,.1,1),"gain1 equivalence");

        // Real state-machine integration: active lock cannot bypass stale input, Stop or takeover.
        AimingSessionTest.Fake f=new AimingSessionTest.Fake(); f.nearby=true;
        for(int i=0;i<5;i++) f.core.observeDirection(at(80-i*5,70,f.time-2000+i*500,0),f.time-2000+i*500);
        f.aiming(); // initial setup is far; then enter with a fresh left vote history
        f.input=at(30,20,f.time,0); f.core.observeDirection(f.input,f.time); f.core.tick();
        check(f.core.nearbyLock.direction()==-1,"session captures anticlockwise");
        for(int i=0;i<30;i++) {
            f.time+=100; f.input=at(30,20,f.time,0); f.core.observeDirection(f.input,f.time); f.core.tick();
        }
        check(f.sent.get(f.sent.size()-1)<-8,"long route continues left above old cap");
        check(f.core.cycleAngle==-330,"logged angle is directed route");
        f.time+=100; f.input=at(30,4,f.time,0); f.core.tick();
        check(f.core.state()==AimingSession.State.AIMING,"4m does not pause");
        f.time+=100; f.input=at(30,0,f.time,0); f.core.tick();
        check(f.core.state()==AimingSession.State.AIMING && f.sent.get(f.sent.size()-1)==0,"coincident zero without pause");
        f.time+=100; AimingSession.Inputs old=at(30,20,f.time-3001,0);
        f.input=new AimingSession.Inputs(old.target,0,0,0,f.time,null,true); f.core.tick();
        check(f.core.state()==AimingSession.State.PAUSED,"stale target still pauses");
        check(f.core.nearbyLock.direction()==-1,"pause retains lock");
        f.core.stopAiming("user_stop"); check(f.core.nearbyLock.direction()==0 && !f.core.maySendYaw(),"stop clears and suppresses");
        f=new AimingSessionTest.Fake(); f.nearby=true; f.aiming(); f.input=at(30,20,f.time,0); f.core.tick();
        f.lost=true; f.core.tick(); check(f.core.nearbyLock.direction()==0 && !f.core.maySendYaw(),"takeover clears lock");
        f=new AimingSessionTest.Fake(); f.aiming(); f.input=at(30,4,f.time,0); f.core.tick();
        check(f.core.state()==AimingSession.State.AIMING && f.core.nearbyLock.direction()==0,"toggleOFF removes distance pause but no lock");
        lock=new NearbyDirectionLock(); lock.update(true,true,20,1,1000);
        check(lock.update(true,false,20,-1,301000).startsWith("refreshed") && lock.direction()==-1,
                "fresh paused geometry refreshes held lock at hard expiry");
        f=new AimingSessionTest.Fake(); f.nearby=true; f.aiming(); f.input=at(30,20,f.time,0); f.core.tick();
        final AimingSessionTest.Fake cancelled=f; final int[] reads={0};
        int before=f.sent.size(); f.onRead=() -> { if(++reads[0]==2) cancelled.core.cancelImmediately(); };
        f.time+=100; f.core.tick();
        check(!f.core.maySendYaw() && f.sent.subList(before,f.sent.size()).stream().allMatch(v -> v==0),
                "late cancellation vetoes locked-route yaw");
        System.out.println("PASS: "+count+" goalkeeper assertions (votes, timers, routes, rates, pauses and control guards)");
    }
}
