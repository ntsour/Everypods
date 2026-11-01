import unittest
from check_audio_authority import ROOT, SERVICE, check_sources, mask_kotlin


class AudioAuthorityTest(unittest.TestCase):
    def test_current_source_inventory(self):
        sources = {str(p.relative_to(ROOT)): p.read_text(encoding='utf-8') for p in ROOT.rglob('*.kt')}
        self.assertEqual([], check_sources(sources))
        sources['unexpected/Caller.kt'] = 'fun bypass() { connectAudio() }'
        self.assertTrue(any('connectAudio' in e for e in check_sources(sources)))
        del sources['unexpected/Caller.kt']
        sources[SERVICE] = sources[SERVICE].replace(
            'return lidA2dpConnectionStateOrNull(target) == BluetoothProfile.STATE_CONNECTED',
            'return isConnected() || lidA2dpConnectionStateOrNull(target) == BluetoothProfile.STATE_CONNECTED')
        self.assertTrue(any('AACP connectivity' in e for e in check_sources(sources)))

    def test_automatic_control_socket_guard_cannot_be_removed(self):
        sources = {str(p.relative_to(ROOT)): p.read_text(encoding='utf-8') for p in ROOT.rglob('*.kt')}
        sources[SERVICE] = sources[SERVICE].replace('!manual && !automaticControlSocketAllowed(device)', 'false')
        self.assertTrue(any('automatic control socket' in e for e in check_sources(sources)))

    def test_manual_reconnect_cannot_wait_for_the_control_socket(self):
        sources = {str(p.relative_to(ROOT)): p.read_text(encoding='utf-8') for p in ROOT.rglob('*.kt')}
        sources[SERVICE] = sources[SERVICE].replace('submitExplicitAudioIntent(device,\n                    io.automated.ventures.everypods.bluetooth.audio.AudioConnectionCoordinator.Trigger.MANUAL,\n                    manualToken)', 'blockedAudioIntent()')
        self.assertTrue(any('manual reconnect' in e for e in check_sources(sources)))

    def test_hidden_profile_calls_are_confined_to_the_android_adapter(self):
        sources = {str(p.relative_to(ROOT)): p.read_text(encoding='utf-8') for p in ROOT.rglob('*.kt')}
        sources['unexpected/Bypass.kt'] = 'fun bypass(x: Any) { x.javaClass.getMethod("connect", Any::class.java) }'
        self.assertTrue(any('reflection inventory' in e for e in check_sources(sources)))

    def test_comments_and_strings_are_not_call_sites(self):
        source = '/* outer /* connectAudio() */ */ val note = "connectAudio()" // connectAudio()\nconnectAudio()'
        self.assertEqual(1, mask_kotlin(source).count('connectAudio('))


if __name__ == '__main__':
    unittest.main()
