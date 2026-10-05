from pathlib import Path
import shutil
root=Path('C:/Users/gildo/.codex/worktrees/vt/gdlapp/vt')
pkg='dji/v5/ux/sample/showcase/defaultlayout/aiming'
src=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/main/java'/pkg
test=root/'SampleCode-V5/android-sdk-v5-uxsdk/src/test/java'/pkg
shutil.copyfile(Path('C:/Users/gildo/gdlapp/cam3/exports/vt404/Vt404Test.java'),test/'Vt404Test.java')
p=src/'AngleRoutePlanner.java';s=p.read_text(encoding='utf-8').replace('public int failedSegment=-1;','public int failedSegment=-1;\n        public double[] clockwiseFailureValues,anticlockwiseFailureValues;')
s=s.replace('if(direction==1)result.clockwiseFailure=reason;else result.anticlockwiseFailure=reason;','''double[] values=firstFailure==null?null:new double[]{failureIndex,firstFailure.clearance,firstFailure.boundary,firstFailure.excursion};
                if(direction==1){result.clockwiseFailure=reason;result.clockwiseFailureValues=values;}
                else {result.anticlockwiseFailure=reason;result.anticlockwiseFailureValues=values;}''')
s=s.replace('best.clockwiseFailure=result.clockwiseFailure;best.anticlockwiseFailure=result.anticlockwiseFailure;','best.clockwiseFailure=result.clockwiseFailure;best.anticlockwiseFailure=result.anticlockwiseFailure;\n        best.clockwiseFailureValues=result.clockwiseFailureValues;best.anticlockwiseFailureValues=result.anticlockwiseFailureValues;')
p.write_text(s,encoding='utf-8')
p=src/'ComeToMeController.java';s=p.read_text(encoding='utf-8').replace('public int failedSegment=-1;','public double[] clockwiseFailureValues,anticlockwiseFailureValues;\n    public int failedSegment=-1;')
s=s.replace('clockwiseFailure=p.clockwiseFailure;anticlockwiseFailure=p.anticlockwiseFailure;','clockwiseFailure=p.clockwiseFailure;anticlockwiseFailure=p.anticlockwiseFailure;\n            clockwiseFailureValues=p.clockwiseFailureValues;anticlockwiseFailureValues=p.anticlockwiseFailureValues;')
s=s.replace('clockwiseFailure=anticlockwiseFailure="none";failedSegment=-1;','clockwiseFailure=anticlockwiseFailure="none";clockwiseFailureValues=anticlockwiseFailureValues=null;failedSegment=-1;')
# Submission must enforce exactly the same zero-boundary rule as planning.
s=s.replace('if(angleApproach()&&(route==null||!angleLegAllowed(in)))return false;','''if(angleApproach()&&(route==null||!angleLegAllowed(in)))return false;
        if(angleApproach()&&positioning.boundaryDistance(in.lat,in.lon,initialCentralLat,initialCentralLon)<=0){
            double h=Math.toRadians(in.heading);
            double normal=requestedForward*(Math.cos(h)*positioning.shore.seaNorth+Math.sin(h)*positioning.shore.seaEast)
                +requestedRight*(-Math.sin(h)*positioning.shore.seaNorth+Math.cos(h)*positioning.shore.seaEast);
            if(normal< -1e-6)return false;
        }''')
p.write_text(s,encoding='utf-8')
p=src/'MovementCycleLog.java';s=p.read_text(encoding='utf-8').replace('"failedSegmentIndex",m.failedSegment,','''"candidateFailureValueOrder","segmentIndex,clearanceM,boundaryM,excursionM",
                "clockwiseFailureValues",m.clockwiseFailureValues,"anticlockwiseFailureValues",m.anticlockwiseFailureValues,
                "routeWaypointLatitude",m.route==null?null:m.waypointLat(),"routeWaypointLongitude",m.route==null?null:m.waypointLon(),
                "filmingEndpointDirectDistanceM",m.angleApproach()&&Double.isFinite(m.approachTargetLat)?YawAimingMath.distance(m.approachTargetLat,m.approachTargetLon,m.routeSurferLat,m.routeSurferLon):null,
                "failedSegmentIndex",m.failedSegment,''')
p.write_text(s,encoding='utf-8')
p=root/'tools/test-aiming.ps1';s=p.read_text(encoding='utf-8');s+='''
foreach($routeType in @('direct','around')) {
    $angleRows=@(Get-Content (Join-Path $output "vt404-$routeType.jsonl") | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object event -eq 'movement_cycle')
    $row=$angleRows[0]
    if($angleRows.Count -ne 1 -or $row.routeKind -ne $routeType -or $row.distanceMeaning -ne 'direct_horizontal' -or $null -ne $row.projectedSeparationM -or $row.maxExcursionM -ne 300 -or $row.excursionStopDistanceM -ne 299 -or $row.routeWaypoints.Count -lt 1 -or $row.routeWaypoints[0].Count -ne 2 -or $row.fixedDestinationFixTime -le 0 -or $row.yawPurpose -ne 'surfer' -or $null -eq $row.submittedRightMps){throw 'VT404 independent route log verification failed'}
}
Write-Output 'PASS: independent JSON parser validates direct/around waypoint arrays, snapshot, distance meaning, excursion and actual BODY submission'
''';p.write_text(s,encoding='utf-8')
print('VT404 final-snapshot and log tests added')
