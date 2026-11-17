"""Controller evidence available at each GPS receipt; never inferred from map motion."""
import math
import re

STATUS_MAX_AGE_MS = 1000
STATUS_FIELDS = {
    'aiming_cycle': ('state', 'reason', 'decision', 'aimingMode', 'submitted', 'submittedYawRate',
                     'reverseBlocked', 'reverseBlockRemainingMs', 'retainedTargetAiming'),
    'movement_cycle': ('enabled', 'phase', 'reason', 'paused', 'gpsMovementPaused', 'pathCheckStatus',
                       'pathBlockReason', 'currentBoundaryDistanceM', 'retreatBoundaryStatus',
                       'retreatBoundaryBlocked', 'retreatBoundarySeawardDistanceM', 'retreatActive',
                       'retreatCooldownRemainingMs', 'rideRemainingMs', 'submittedForwardMps', 'submittedRightMps'),
    'retreat_cycle': ('active', 'reason', 'boundaryStatus', 'boundaryBlocked', 'boundarySeawardDistanceM'),
}
RESET_EVENTS = ('session_start', 'session_end', 'user_start', 'stop', 'state')
STATUS_EVENTS = tuple(STATUS_FIELDS) + RESET_EVENTS


def status_record(record, time_ms, line):
    event = record.get('event')
    if event not in STATUS_EVENTS:
        return None
    fields = STATUS_FIELDS.get(event, ('detail', 'reason'))
    return dict({key: record.get(key) for key in fields}, event=event, timeMs=time_ms,
                sourceLine=line, session=record.get('session'), cycleId=record.get('cycleId'))


def annotate_drone_status(packets, records):
    """Called separately for each file. Equal timestamps obey original line order."""
    records.sort(key=lambda r: (r['timeMs'], r['sourceLine']))
    index, session, state = 0, None, {}
    for packet in sorted(packets, key=lambda p: (p['time_ms'], p['sourceLine'])):
        cutoff = (packet['time_ms'], packet['sourceLine'])
        while index < len(records) and (records[index]['timeMs'], records[index]['sourceLine']) <= cutoff:
            record = records[index]
            index += 1
            event = record['event']
            if event in RESET_EVENTS:
                state, session = {}, None
                detail = record.get('detail') or ''
                match = re.search(r'session=(\d+)', detail)
                session = int(match[1]) if match else record.get('session')
                transition = re.search(r'-> ([A-Z_]+) reason=(\S+)', detail)
                if event == 'state' and transition:
                    state['aiming'] = dict(record, session=session, state=transition[1], reason=transition[2])
                elif event in ('stop', 'user_start'):
                    reason = re.search(r'reason=(\S+)', detail)
                    state['aiming'] = dict(record, session=session, state='STOPPING' if event == 'stop' else 'STARTING',
                                          reason=reason[1] if reason else record.get('reason') or 'requested')
                continue
            if record['session'] is not None:
                if session is not None and record['session'] != session:
                    state = {}
                session = record['session']
            state[event.removesuffix('_cycle')] = record
        packet['droneStatus'] = dict(state) if packet.get('session') is None or session is None or packet['session'] == session else {}


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def fresh_status(point, kind):
    row = (point.get('droneStatus') or {}).get(kind)
    if not row:
        return None, 'unknown (no log)'
    age = point.get('time_ms', 0) - row['timeMs']
    if age < 0:
        return None, 'unknown (no prior log)'
    if age > STATUS_MAX_AGE_MS:
        return None, f'unknown (log {age/1000:.1f} s old)'
    return row, None


REASONS = {
    'aligned_zero': 'aligned', 'surfer_correction': 'tracking surfer', 'riding_correction': 'tracking surfer during ride',
    'reverse_blocked': 'yaw reversal cooldown', 'return_alignment': 'aligning for return',
    'approach_heading': 'aligning for approach', 'coincident_zero': 'same GPS position', 'no_command': 'no command sent',
    'ride_detected': 'ride detected', 'retreat_or_cooldown': 'retreat or cooldown',
    'moving_saved_route_aiming_surfer': 'following saved route', 'moving_to_fixed_destination_aiming_surfer': 'moving to filming position',
    'moving_to_restart_target': 'moving to return target', 'within_distance_and_angle_tolerance': 'within distance/angle tolerance',
    'within_reapproach_margin': 'within reapproach margin', 'within_projected_margin': 'within projected margin',
    'destination_central_boundary': 'destination beyond boundary', 'route_central_boundary': 'route crosses boundary',
    'return_central_boundary': 'return crosses boundary', 'return_boundary_or_excursion_blocked': 'return boundary / excursion limit',
    'route_surfer_clearance': 'route too close to surfer', 'route_planning_clearance': 'route inside planning clearance',
    'destination_inside_planning_clearance': 'destination inside planning clearance',
    'start_inside_planning_clearance': 'start inside planning clearance',
    'no_valid_around_route': 'no valid route around surfer', 'no_permitted_outward_escape': 'no allowed outward route',
    'route_excursion_limit': 'route exceeds excursion limit', 'destination_excursion_limit': 'destination exceeds excursion limit',
    'waiting_fresh_planning_fix': 'waiting for fresh surfer GPS', 'qualified_waiting_fresh_gps': 'waiting for fresh surfer GPS',
}
BOUNDARY_REASONS = {'destination_central_boundary', 'route_central_boundary', 'return_central_boundary',
                    'return_boundary_or_excursion_blocked'}
BLOCK_REASONS = BOUNDARY_REASONS | {'route_surfer_clearance', 'route_planning_clearance',
    'destination_inside_planning_clearance', 'start_inside_planning_clearance', 'no_valid_around_route',
    'no_permitted_outward_escape', 'route_excursion_limit', 'destination_excursion_limit', 'alignment_path_or_destination_blocked'}


def words(value):
    return REASONS.get(value, value.replace('_', ' ')) if isinstance(value, str) and value else 'unknown'


def timer(point, row, key):
    value = row.get(key)
    return f'{max(0, value-(point["time_ms"]-row["timeMs"]))/1000:.1f} s left' if number(value) else None


def current_movement_block(movement, aiming):
    if not movement or movement.get('paused') is True or movement.get('gpsMovementPaused') is True:
        return None
    if aiming and aiming.get('state') not in (None, 'AIMING'):
        return None
    reason = movement.get('reason')
    if reason in BLOCK_REASONS:
        path = movement.get('pathBlockReason')
        return path if reason == 'alignment_path_or_destination_blocked' and path not in (None, 'none') else reason
    return None


def drone_status_lines(point):
    aiming, aiming_missing = fresh_status(point, 'aiming')
    movement, movement_missing = fresh_status(point, 'movement')
    retreat, _ = fresh_status(point, 'retreat')
    if aiming:
        state = aiming.get('state') or 'unknown'
        reason = aiming.get('decision') if state == 'AIMING' else aiming.get('reason')
        text = f'Aiming: {state.replace("_", " ")} — {words(reason or aiming.get("reason"))}'
        if aiming.get('submitted') is True and number(aiming.get('submittedYawRate')):
            text += f' · yaw {aiming["submittedYawRate"]:.1f}°/s sent'
        elif aiming.get('submitted') is False and reason != 'no_command':
            text += ' · no command sent'
        if aiming.get('reverseBlocked') is True:
            remaining = timer(point, aiming, 'reverseBlockRemainingMs')
            if remaining: text += ' · '+remaining
        if aiming.get('retainedTargetAiming') is True: text += ' · last GPS target'
    else:
        text = 'Aiming: '+aiming_missing
    lines = [text]
    block = current_movement_block(movement, aiming)
    if movement:
        phase = movement.get('phase') or 'unknown'
        reason = movement.get('reason')
        if movement.get('paused') is True or aiming and aiming.get('state') == 'PAUSED':
            text = 'PAUSED — '+words(aiming.get('reason') if aiming else reason)
        elif phase != 'OFF' and aiming and aiming.get('state') not in (None, 'AIMING'):
            text = 'suspended — aiming '+words(aiming.get('state')).lower()
        elif movement.get('gpsMovementPaused') is True:
            text = 'WAITING — fresh surfer GPS needed'
        else:
            text = phase+' — '+('BLOCKED: '+words(block) if block else words(reason))
            remaining = None
            if reason == 'ride_detected': remaining = timer(point, movement, 'rideRemainingMs')
            elif reason == 'retreat_or_cooldown':
                if movement.get('retreatActive') is True: text = phase+' — retreat active'
                elif number(movement.get('retreatCooldownRemainingMs')) and movement['retreatCooldownRemainingMs'] > 0:
                    text = phase+' — retreat cooldown'
                    remaining = timer(point, movement, 'retreatCooldownRemainingMs')
            if remaining: text += ' · '+remaining
            if phase in ('APPROACHING', 'RETURNING') and not block:
                forward, right = movement.get('submittedForwardMps'), movement.get('submittedRightMps')
                if number(forward) and number(right): text += f' · move command {math.hypot(forward,right):.1f} m/s'
                elif 'submittedForwardMps' in movement: text += ' · no move command logged'
        lines.append('Come to Me: '+text)
    else:
        lines.append('Come to Me: '+movement_missing)
    # Check navigation vetoes separately from retreat's boundary guard. An
    # inactive retreat does not mean that a planned approach route is allowed.
    boundary_status = retreat.get('boundaryStatus') if retreat else movement.get('retreatBoundaryStatus') if movement else None
    retreat_blocked = retreat.get('boundaryBlocked') if retreat else movement.get('retreatBoundaryBlocked') if movement else None
    distance = movement.get('currentBoundaryDistanceM') if movement else None
    if not number(distance):
        distance = retreat.get('boundarySeawardDistanceM') if retreat else movement.get('retreatBoundarySeawardDistanceM') if movement else None
    if aiming and aiming.get('state') not in (None, 'AIMING'):
        status = 'not evaluated — aiming '+words(aiming.get('state')).lower()
        distance = None
    elif block in BOUNDARY_REASONS:
        status = 'BLOCKED — '+words(block)
    elif boundary_status == 'anchor_unavailable': status = 'unknown — anchor unavailable'
    elif retreat_blocked is True: status = 'BLOCKED — retreat'
    elif boundary_status == 'slowing': status = 'slowing retreat'
    elif retreat_blocked is False: status = 'no block reported'
    else: status = 'unknown'
    if number(distance):
        status += ' · '+('at line' if abs(distance)<.05 else f'{abs(distance):.1f} m '+('seaward' if distance>0 else 'shoreward'))
    lines.append('Boundary: '+status)
    return lines


def drone_status_color(text):
    if not text.startswith(('Aiming:', 'Come to Me:', 'Boundary:')):
        return None
    if any(word in text for word in ('BLOCKED', 'STOPPED', 'RELEASE UNCONFIRMED')): return '#c40000'
    if any(word in text for word in ('PAUSED', 'WAITING', 'suspended', 'slowing', 'cooldown')): return '#a94700'
    if 'unknown' in text: return '#64748b'
    if text.startswith(('Aiming: AIMING', 'Come to Me: APPROACHING', 'Come to Me: RETURNING')): return '#08752e'
    return '#17324d'
