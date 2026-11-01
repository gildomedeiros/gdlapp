"""Sidebar metadata for real GPS points and their logged ride windows."""


def map_filter_data(points):
    from surfer_gps_map import drone_next_bearing
    # Timestamp selections have no precomputed full-log bearing field. Use the
    # same real-neighbour calculation as the SVG, retaining cached bearings in
    # ride/pitch selections where the next packet may be outside the selection.
    drone_bearings={p['point']:drone_next_bearing(points,i) for i,p in enumerate(points)}
    rides = {}
    rows = []
    for point in sorted(points, key=lambda p: (p['time_ms'], p['sourceFile'], p.get('sourceLine', 0))):
        offsets = {}
        for window in point.get('mapRideWindows', []):
            ride = rides.setdefault(window['id'], dict(window, points=[]))
            ride['points'].append(point['point'])
            offsets[window['id']] = (point['time_ms']-window['startMs'])/1000
        rows.append(dict(id=point['point'], time=point['receivedTimestamp'],
                         distance=point.get('droneSurferDistanceMetres'), pitch=point.get('gimbalTiltDeg'),
                         offsets=offsets, accepted=point.get('accepted'), waveRiding=point.get('waveRiding'),
                         speedKmh=point.get('surferSpeedSincePreviousKmh'),
                         surferBearing=point.get('surferNextBearingDeg'), droneBearing=drone_bearings[point['point']]))
    return dict(rides=sorted(rides.values(), key=lambda r: r['number']), points=rows)
