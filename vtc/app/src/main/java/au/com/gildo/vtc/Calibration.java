package au.com.gildo.vtc;

final class Calibration {
    final int panRaw,tiltRaw,panCpr,tiltCpr;
    final double bearing,elevation;
    final Settings settings;
    Calibration(Settings s,GtiClient.Position p,int[] cpr) {
        s.calibrationReady();
        settings=s;panRaw=p.pan;tiltRaw=p.tilt;panCpr=cpr[0];tiltCpr=cpr[1];
        bearing=Geo.bearing(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon);
        elevation=Geo.elevation(Geo.distance(s.latitude,s.longitude,s.landmarkLat,s.landmarkLon),s.height,s.landmarkHeight);
        if(elevation<s.tiltMin||elevation>s.tiltMax)throw new IllegalArgumentException("Landmark elevation is outside the configured tilt limits");
    }
    double panOffset(int raw){return GtiClient.deltaCounts(raw,panRaw,panCpr)*360.0/panCpr*settings.panSign;}
    double tiltAngle(int raw){return elevation+GtiClient.deltaCounts(raw,tiltRaw,tiltCpr)*360.0/tiltCpr*settings.tiltSign;}
    boolean allowsManualMove(GtiClient.Position here,double pan,double tilt) {
        if(!allowed(panOffset(here.pan)+pan,tiltAngle(here.tilt)+tilt))return false;
        int panTarget=GtiClient.wrap24(here.pan+(int)Math.round(pan*settings.panSign*panCpr/360.0));
        int tiltTarget=GtiClient.wrap24(here.tilt+(int)Math.round(tilt*settings.tiltSign*tiltCpr/360.0));
        return allowed(panOffset(panTarget),tiltAngle(tiltTarget));
    }
    double heading(int raw){return Geo.wrap(bearing+panOffset(raw));}
    double targetPan(double heading){return Geo.delta(heading,bearing);}
    boolean allowed(double pan,double tilt){return Double.isFinite(pan)&&Double.isFinite(tilt)&&pan>=settings.panMin&&pan<=settings.panMax&&tilt>=settings.tiltMin&&tilt<=settings.tiltMax;}
}

