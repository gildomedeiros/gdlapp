"""Map references from the configuration and movement state logged at each sample."""
import html
import json
import math
from pathlib import Path

from telemetry_around import record_milliseconds


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def coordinate(lat, lon):
    if number(lat) and number(lon) and -90 <= lat <= 90 and -180 <= lon <= 180:
        return dict(latitude=lat, longitude=lon)


def json_object(value):
    try:
        value = json.loads(value) if isinstance(value, str) else value
    except ValueError:
        return {}
    return value if isinstance(value, dict) else {}


def load_map_landmarks(points, tz):
    sources = {}
    for point in points:
        sources.setdefault(point['sourceFile'], []).append(point)
    found = {}
    for source, samples in sources.items():
        if not Path(source).is_file():
            continue
        samples = sorted(samples, key=lambda p: (p['time_ms'], p.get('sourceLine', math.inf)))
        end = (samples[-1]['time_ms'], samples[-1].get('sourceLine', math.inf))
        records = []
        wanted = ('session_start', 'session_end', 'vt40_configuration', 'movement_cycle')
        with Path(source).open(encoding='utf-8-sig') as stream:
            for line_no, line in enumerate(stream, 1):
                if not any('"'+event+'"' in line for event in wanted):
                    continue
                record = json.loads(line)
                if not isinstance(record, dict) or record.get('event') not in wanted:
                    continue
                key = (record_milliseconds(record, tz), line_no)
                if key > end:
                    continue
                fields = ('event', 'session', 'nextSession', 'effectiveSettingsJson', 'effectiveWaveLinesJson',
                          'waveLineId', 'seawardBearingDeg', 'retreatBoundaryLatitude', 'retreatBoundaryLongitude')
                records.append((key, {k: record.get(k) for k in fields}))
        records.sort(key=lambda row: row[0])
        config, movement, start, session = None, None, None, None
        index = 0
        for sample in samples:
            key = (sample['time_ms'], sample.get('sourceLine', math.inf))
            while index < len(records) and records[index][0] <= key:
                state_key, r = records[index]
                index += 1
                event = r['event']
                if event in ('session_start', 'session_end'):
                    config, movement, start, session = None, None, None, None
                elif event == 'vt40_configuration':
                    settings = json_object(r['effectiveSettingsJson'])
                    library = json_object(r['effectiveWaveLinesJson']).get('waveLines', [])
                    config = dict(session=r['nextSession'], selected=settings.get('waveLineId'),
                                  profiles={p['id']: p for p in library if isinstance(p, dict) and isinstance(p.get('id'),str)} if isinstance(library, list) else {})
                else:
                    if r['session'] != session:
                        session, start = r['session'], None
                    movement = r
                    anchor = coordinate(r['retreatBoundaryLatitude'], r['retreatBoundaryLongitude'])
                    if anchor and start is None:
                        # VT's retreat boundary anchor is initialCentralLat/Lon:
                        # the original drone position captured after Start.
                        start = dict(anchor, time_ms=state_key[0], sourceLine=state_key[1])
            if movement is None or (sample.get('session') is not None and sample['session'] != session):
                continue
            context = dict(sourceFile=source, session=session)
            references = []
            if start:
                references.append(dict(context, kind='start', label='Start (VT control)', **start))
            anchor = coordinate(movement['retreatBoundaryLatitude'], movement['retreatBoundaryLongitude'])
            bearing = movement['seawardBearingDeg']
            if anchor and number(bearing):
                references.append(dict(context, kind='boundary', label='Boundary line', **anchor, bearingDegrees=(bearing+90)%180))
            if config and (config['session'] is None or session is None or config['session'] == session):
                ident = movement['waveLineId'] or config['selected']
                profile = config['profiles'].get(ident) if isinstance(ident,str) else None
                if profile:
                    a, b = json_object(profile.get('pointA')), json_object(profile.get('pointB'))
                    a, b = coordinate(a.get('latitude'), a.get('longitude')), coordinate(b.get('latitude'), b.get('longitude'))
                    if a and b and a != b:
                        references.append(dict(context, kind='wave', label='Wave line · '+str(profile.get('name') or ident),
                                               waveLineId=ident, pointA=a, pointB=b))
            for reference in references:
                signature = json.dumps(reference, sort_keys=True)
                if signature not in found:
                    found[signature] = dict(reference, pairs=[])
                found[signature]['pairs'].append(sample['point'])
    return list(found.values())


def landmark_coordinates(references):
    for ref in references:
        if ref['kind'] == 'wave':
            yield ref['pointA']
            yield ref['pointB']
        else:
            yield ref


def render_landmarks(references, project):
    out = ['<g id="map-landmarks" pointer-events="none">']
    classes = dict(wave='wave-line', boundary='boundary-line', start='start-point')
    for ref in references:
        kind, label = ref['kind'], html.escape(ref['label'])
        pairs = html.escape(' '.join(ref['pairs']), quote=True)
        tooltip = html.escape(ref['label']+' · '+Path(ref['sourceFile']).name+' · session '+str(ref.get('session')))
        out.append(f'<g class="map-landmark {classes[kind]}" data-pairs="{pairs}"><title>{tooltip}</title>')
        coords = list(landmark_coordinates([ref]))
        locations = [project(p['latitude'], p['longitude']) for p in coords]
        for (x, y), geo in zip(locations, coords):
            out.append(f'<g class="landmark-anchor" data-x="{x}" data-y="{y}" data-lat="{geo["latitude"]}" data-lon="{geo["longitude"]}"/>')
        x, y = locations[0]
        if kind == 'wave':
            bx, by = locations[1]
            out.append(f'<line x1="{x}" y1="{y}" x2="{bx}" y2="{by}" stroke="#007d98" stroke-width="3" vector-effect="non-scaling-stroke"/>')
            for px, py in locations:
                out.append(f'<circle class="landmark-dot" cx="{px}" cy="{py}" r="4" fill="#007d98"/>')
        elif kind == 'boundary':
            angle = math.radians(ref['bearingDegrees'])
            dx, dy = math.sin(angle), -math.cos(angle)
            out.append(f'<line class="boundary-stroke" data-x="{x}" data-y="{y}" data-dx="{dx}" data-dy="{dy}" x1="{x-dx*1500}" y1="{y-dy*1500}" x2="{x+dx*1500}" y2="{y+dy*1500}" stroke="#c52b34" stroke-width="2.5" stroke-dasharray="8 5" vector-effect="non-scaling-stroke"/>')
        else:
            out.append(f'<circle class="landmark-dot start-dot" cx="{x}" cy="{y}" r="7" fill="#7036a5" stroke="white" stroke-width="2" vector-effect="non-scaling-stroke"/>')
        out.append(f'<text class="landmark-label" data-x="{x}" data-y="{y}" x="{x+10}" y="{y-10}" fill="#17324d" font-size="13" stroke="#f3f7fa" stroke-width="3" paint-order="stroke">{label}</text></g>')
    out.append('</g>')
    return ''.join(out)
