package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import java.util.*;

/** Snapshot geometry only. All actual motion stays in the session/controller. */
public final class AngleRoutePlanner {
    private static final int N=72;
    public static final class Check {
        public String reason="none";
        public double clearance,boundary,excursion;
    }
    public static final class Plan {
        public double[][] points=new double[0][];
        public String reason="none",kind="direct",direction="none",clockwiseFailure="none",anticlockwiseFailure="none";
        public double length,failedClearance=Double.NaN,failedBoundary=Double.NaN,failedExcursion=Double.NaN;
        public int failedSegment=-1;
        void failure(Check c,int index){reason=c.reason;failedSegment=index;failedClearance=c.clearance;failedBoundary=c.boundary;failedExcursion=c.excursion;}
    }
    public static Check check(ShorelinePositioning p,double aLat,double aLon,double bLat,double bLon,
            double surferLat,double surferLon,double anchorLat,double anchorLon,double centralLat,double centralLon) {
        Check c=new Check();
        double[] a=p.shore.offset(aLat,aLon,surferLat,surferLon),b=p.shore.offset(bLat,bLon,surferLat,surferLon);
        c.clearance=ShorelineGeometry.segmentDistance(a[0],a[1],b[0],b[1]);
        c.boundary=Math.min(p.boundaryDistance(aLat,aLon,anchorLat,anchorLon),p.boundaryDistance(bLat,bLon,anchorLat,anchorLon));
        c.excursion=Math.max(YawAimingMath.distance(aLat,aLon,centralLat,centralLon),YawAimingMath.distance(bLat,bLon,centralLat,centralLon));
        if(!Double.isFinite(c.boundary)||c.boundary< -1e-6)c.reason="route_central_boundary";
        else if(c.excursion>p.excursionStop+1e-6)c.reason="route_excursion_limit";
        else if(c.clearance+1e-6<p.clearance())c.reason="route_surfer_clearance";
        return c;
    }
    public static Plan plan(ShorelinePositioning p,AimingSession.Inputs in,double[] end,
            double anchorLat,double anchorLon,double centralLat,double centralLon) {
        Plan result=new Plan();double sl=in.target.lat,so=in.target.lon;
        Check destination=check(p,end[0],end[1],end[0],end[1],sl,so,anchorLat,anchorLon,centralLat,centralLon);
        if(!destination.reason.equals("none")){
            if(destination.reason.equals("route_central_boundary"))destination.reason="destination_central_boundary";
            else if(destination.reason.equals("route_excursion_limit"))destination.reason="destination_excursion_limit";
            result.failure(destination,-1);return result;
        }
        Check start=check(p,in.lat,in.lon,in.lat,in.lon,sl,so,anchorLat,anchorLon,centralLat,centralLon);
        if(!start.reason.equals("none")){
            if(start.reason.equals("route_central_boundary"))start.reason="start_beachward_of_boundary";
            else if(start.reason.equals("route_surfer_clearance"))start.reason="start_inside_routing_clearance";
            result.failure(start,0);return result;
        }
        Check direct=check(p,in.lat,in.lon,end[0],end[1],sl,so,anchorLat,anchorLon,centralLat,centralLon);
        if(p.correctQuadrant(in)&&direct.reason.equals("none")){
            result.points=new double[][]{end};result.length=YawAimingMath.distance(in.lat,in.lon,end[0],end[1]);return result;
        }
        double startRadius=YawAimingMath.distance(in.lat,in.lon,sl,so),endRadius=YawAimingMath.distance(end[0],end[1],sl,so);
        // OFF: geometric radius only, no retreat-circle exclusion. ON: exterior polygon
        // accounts for the one-metre waypoint completion envelope as well as chord sag.
        double radius=p.clearance()>0?(p.clearance()+1.1)/Math.cos(Math.PI/N):Math.min(startRadius,endRadius)/Math.cos(Math.PI/N);
        double[][] ring=new double[N][];Check[] entry=new Check[N],exit=new Check[N];
        for(int i=0;i<N;i++){
            double a=2*Math.PI*i/N;ring[i]=p.shore.point(sl,so,radius*Math.cos(a),radius*Math.sin(a));
            entry[i]=check(p,in.lat,in.lon,ring[i][0],ring[i][1],sl,so,anchorLat,anchorLon,centralLat,centralLon);
            exit[i]=check(p,ring[i][0],ring[i][1],end[0],end[1],sl,so,anchorLat,anchorLon,centralLat,centralLon);
        }
        Plan best=null;
        for(int direction:new int[]{1,-1}){
            Plan candidate=new Plan();candidate.kind="around";candidate.direction=direction==1?"clockwise":"anticlockwise";
            double bestLength=Double.POSITIVE_INFINITY;List<double[]> bestPoints=null;Check firstFailure=null;int failureIndex=-1;
            for(int i=0;i<N;i++){
                if(!entry[i].reason.equals("none")){if(firstFailure==null){firstFailure=entry[i];failureIndex=0;}continue;}
                List<double[]> points=new ArrayList<>();points.add(ring[i]);
                double length=YawAimingMath.distance(in.lat,in.lon,ring[i][0],ring[i][1]);int current=i;
                for(int step=1;step<N;step++){
                    int next=(current+direction+N)%N;
                    Check edge=check(p,ring[current][0],ring[current][1],ring[next][0],ring[next][1],sl,so,anchorLat,anchorLon,centralLat,centralLon);
                    if(!edge.reason.equals("none")){if(firstFailure==null){firstFailure=edge;failureIndex=step;}break;}
                    length+=YawAimingMath.distance(ring[current][0],ring[current][1],ring[next][0],ring[next][1]);
                    points.add(ring[next]);current=next;
                    if(exit[current].reason.equals("none")){
                        double total=length+YawAimingMath.distance(ring[current][0],ring[current][1],end[0],end[1]);
                        if(total<bestLength){bestLength=total;bestPoints=new ArrayList<>(points);bestPoints.add(end);}
                    }else if(firstFailure==null){firstFailure=exit[current];failureIndex=step+1;}
                }
            }
            if(bestPoints!=null){candidate.points=bestPoints.toArray(new double[0][]);candidate.length=bestLength;
                if(best==null||candidate.length<best.length-1e-6)best=candidate;
            }else{
                String reason=firstFailure==null?"no_geometric_around_route":firstFailure.reason;
                if(direction==1)result.clockwiseFailure=reason;else result.anticlockwiseFailure=reason;
                if(firstFailure!=null&&result.failedSegment<0){result.failure(firstFailure,failureIndex);}
            }
        }
        if(best==null){result.reason="no_valid_around_route";return result;}
        best.clockwiseFailure=result.clockwiseFailure;best.anticlockwiseFailure=result.anticlockwiseFailure;
        return best;
    }
}
