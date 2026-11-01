#!/usr/bin/env python3
"""Narrow source guard for the current Android audio-command migration.

This catches added call sites and reflective Bluetooth commands; it is not a
Kotlin parser or a proof of runtime ordering. Update only after reviewing any
new command path and its coordinator fencing.
"""
from collections import Counter
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1] / 'android/app/src/main/java'
SERVICE = 'io/automated/ventures/everypods/services/AirPodsService.kt'
ADAPTER = 'io/automated/ventures/everypods/bluetooth/audio/AudioProfileAdapter.kt'
BACKEND = 'io/automated/ventures/everypods/bluetooth/audio/AndroidAudioProfileBackend.kt'
HIDDEN = 'io/automated/ventures/everypods/bluetooth/audio/AndroidHiddenProfileCommands.kt'


def mask_kotlin(source, keep_strings=False):
    """Mask comments (including nested block comments), optionally string contents."""
    chars = list(source)
    i = 0
    def hide(start, end):
        for n in range(start, end):
            if chars[n] != '\n':
                chars[n] = ' '
    while i < len(source):
        start = i
        if source.startswith('//', i):
            end = source.find('\n', i)
            i = len(source) if end == -1 else end
            hide(start, i)
        elif source.startswith('/*', i):
            i += 2
            depth = 1
            while i < len(source) and depth:
                if source.startswith('/*', i): depth += 1; i += 2
                elif source.startswith('*/', i): depth -= 1; i += 2
                else: i += 1
            hide(start, i)
        elif source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = len(source) if end == -1 else end + 3
            if not keep_strings: hide(start, i)
        elif source[i] in ('"', "'"):
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == '\\': i += 2
                elif source[i] == quote: i += 1; break
                else: i += 1
            if not keep_strings: hide(start, min(i, len(source)))
        else:
            i += 1
    return ''.join(chars)


def check_sources(sources):
    calls = Counter()
    constructors = Counter()
    reflection = Counter()
    for path, source in sources.items():
        code = mask_kotlin(source)
        calls[path] += len(re.findall(r'(?<![\w])connectAudio\s*\(', code))
        constructors[path] += len(re.findall(r'(?<![\w])AudioProfileAdapter\s*\(', code))
        uncommented = mask_kotlin(source, keep_strings=True)
        for method in re.findall(r'get(?:Declared)?Method\s*\(\s*"(connect|disconnect|setConnectionPolicy)"', uncommented):
            reflection[(path, method)] += 1
    calls += Counter()  # Drop zero counts.
    constructors += Counter()
    expected_reflection = Counter({(HIDDEN, 'connect'): 1, (HIDDEN, 'disconnect'): 1,
                                   (HIDDEN, 'setConnectionPolicy'): 1})
    failures = []
    service_code = mask_kotlin(sources.get(SERVICE, ''))
    manual_reconnect = re.search(r'fun\s+reconnectFromSavedMac\s*\(\s*\)\s*\{(.*?)\n    \}', service_code, re.DOTALL)
    if (manual_reconnect is None or
            not re.search(r'submitExplicitAudioIntent\s*\(.*?connectToSocket\s*\(', manual_reconnect.group(1), re.DOTALL)):
        failures.append('manual reconnect must submit audio before the potentially blocking control socket')
    socket_entry = re.search(r'fun\s+connectToSocket\s*\(.*?\n    \}', service_code, re.DOTALL)
    socket_loop = re.search(r'fun\s+connectToSocketLocked\s*\(.*?for\s*\(attempt\s+in\s+1\.\.maxAttempts\)\s*\{(.*?)val\s+candidate', service_code, re.DOTALL)
    if (socket_entry is None or 'automaticControlSocketAllowed(device)' not in socket_entry.group(0) or
            socket_loop is None or not re.search(r'!manual\s*&&\s*!automaticControlSocketAllowed\(device\)', socket_loop.group(1))):
        failures.append('automatic control socket entry and retries must require confirmed local audio')
    confirmation = re.search(r'fun\s+hasConfirmedPassiveAudioConnection\s*\([^)]*\)\s*:\s*Boolean\s*\{(.*?)\n    \}', service_code, re.DOTALL)
    if (confirmation is None or 'lidA2dpConnectionStateOrNull' not in confirmation.group(1) or
            'BluetoothProfile.STATE_CONNECTED' not in confirmation.group(1) or
            re.search(r'(?<!\w)isConnected\s*\(', confirmation.group(1))):
        failures.append('passive success must use confirmed A2DP profile state, not AACP connectivity')
    if calls != Counter({SERVICE: 2}):
        failures.append(f'connectAudio declarations/calls changed: {dict(calls)}')
    if constructors != Counter({SERVICE: 2, ADAPTER: 1}):
        failures.append(f'AudioProfileAdapter declarations/callers changed: {dict(constructors)}')
    if reflection != expected_reflection:
        failures.append(f'Bluetooth reflection inventory changed: {dict(reflection)}')
    return failures


def main():
    sources = {str(path.relative_to(ROOT)): path.read_text(encoding='utf-8') for path in ROOT.rglob('*.kt')}
    failures = check_sources(sources)
    if failures:
        print('\n'.join(failures))
        return 1
    print('Audio command call sites and reflective method inventory match the reviewed paths.')
    print('This is a narrow source guard; it does not establish runtime or hardware behavior.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
