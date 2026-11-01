#!/usr/bin/env python3
"""Summarize exported EveryPods case journals without exposing device identifiers.

Each input is one device export in chronological order. The cycle counter resets
at process start, so it is meaningful only within a device/process. A cycle is
an observation grouping, not a verified physical case opening.
"""
import argparse
import json
import re
from collections import OrderedDict
from pathlib import Path

MARKER = '<LogCollector:LidLease>'
FIELDS = re.compile(r'(?<!\S)([A-Za-z][A-Za-z0-9_]*)=([^\s]+)')
INTEGER = re.compile(r'^-?\d+$')
IDENTIFIERS = re.compile(r'(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|\b[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\b|(?<![0-9])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])')


def parse_line(line):
    if MARKER not in line:
        return None
    before, after = line.split(MARKER, 1)
    pieces = after.strip().split(maxsplit=1)
    if not pieces:
        return None
    fields = dict(FIELDS.findall(before + ' ' + (pieces[1] if len(pieces) > 1 else '')))
    return pieces[0], fields


def number(value):
    return int(value) if value is not None and INTEGER.fullmatch(value) else None


def analyze(lines, device):
    groups = OrderedDict()
    process = 0
    for line in lines:
        parsed = parse_line(line)
        if parsed is None:
            continue
        event, fields = parsed
        event = IDENTIFIERS.sub('[redacted]', event)
        if event == 'process_start':
            process += 1
            continue
        cycle = number(fields.get('cycle'))
        if cycle is None:
            continue
        # Do not merge zero or repeated counters across devices or processes.
        key = (device, process, cycle)
        group = groups.setdefault(key, {'device': device, 'process': process, 'cycle': cycle,
                                         'events': [], 'intents': []})
        elapsed = number(fields.get('elapsedMs'))
        wall = number(fields.get('wallMs'))
        # Allowlist output: raw addresses, peer ids and unrelated fields never leave the parser.
        allowed = ('intentId', 'command', 'attempt', 'reason', 'result', 'state', 'previous',
                   'profile', 'delayMs', 'feature', 'holder', 'shared', 'peerHolding', 'path', 'ok',
                   'type', 'role', 'phase', 'participantPhase', 'commandIssued', 'success',
                   'policyRevision', 'revision', 'origin', 'requested', 'effective', 'override')
        entry = {'event': event, 'elapsedMs': elapsed, 'wallMs': wall}
        entry.update({k: IDENTIFIERS.sub('[redacted]', fields[k]) for k in allowed if k in fields})
        group['events'].append(entry)
        intent = entry.get('intentId')
        if intent is not None and intent not in group['intents']:
            group['intents'].append(intent)
    for group in groups.values():
        events = group['events']
        first = next((e['elapsedMs'] for e in events if e['elapsedMs'] is not None), None)
        for event in events:
            event['relativeMs'] = (event['elapsedMs'] - first if first is not None and
                                   event['elapsedMs'] is not None else None)
        # Only a profile observation or explicit coordinator outcome establishes a result.
        result = 'unknown'
        for event in events:
            if event['event'] == 'audio_coordinator_outcome':
                result = event.get('reason', 'unknown')
            elif event['event'] in ('a2dp_outcome', 'profile_state'):
                if event['event'] == 'profile_state' and event.get('profile') != 'a2dp':
                    continue
                state = event.get('state')
                if state in ('connected', '2'):
                    result = 'profile_connected'
                elif state in ('failed_timeout', '0'):
                    result = 'profile_disconnected_or_failed'
        group['result'] = result
    return list(groups.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('files', nargs='+', type=Path, help='one or more exported device journals')
    parser.add_argument('--json', action='store_true', help='emit machine-readable timeline')
    args = parser.parse_args()
    groups = []
    for index, path in enumerate(args.files, 1):
        with path.open(encoding='utf-8', errors='replace') as source:
            groups.extend(analyze(source, f'device-{index}'))
    if args.json:
        print(json.dumps(groups, indent=2))
    else:
        print('device\tprocess\tcycle\tintents\tresult\tevents\tduration_ms')
        for group in groups:
            relative = [e['relativeMs'] for e in group['events'] if e['relativeMs'] is not None]
            print(f"{group['device']}\t{group['process']}\t{group['cycle']}\t"
                  f"{','.join(group['intents']) or '-'}\t{group['result']}\t"
                  f"{len(group['events'])}\t{max(relative) if relative else '-'}")
        print('Cycles are journal observation groups, not physical case-opening counts.')


if __name__ == '__main__':
    main()
