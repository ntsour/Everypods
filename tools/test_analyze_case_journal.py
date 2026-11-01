import unittest
from pathlib import Path
from analyze_case_journal import analyze


class CaseJournalAnalyzerTest(unittest.TestCase):
    def test_sanitized_observations_and_process_boundaries(self):
        fixture = Path(__file__).parent / 'testdata/case-journal-observations.txt'
        with fixture.open() as source:
            groups = analyze(source, 'pixel')
        self.assertEqual([1, 2, 3, 4, 5, 1], [g['cycle'] for g in groups])
        self.assertEqual([1, 1, 1, 1, 1, 2], [g['process'] for g in groups])
        self.assertEqual('unknown', groups[0]['result'])
        self.assertEqual(87936, groups[0]['events'][-1]['relativeMs'])
        self.assertEqual('profile_disconnected_or_failed', groups[1]['result'])
        self.assertEqual('profile_connected', groups[2]['result'])
        self.assertEqual('profile_disconnected_or_failed', groups[3]['result'])
        self.assertEqual('false', groups[4]['events'][0]['feature'])
        self.assertEqual('unresolved', groups[5]['result'])
        self.assertEqual(['42'], groups[5]['intents'])
        self.assertNotIn('AA:BB:CC:DD:EE:FF', repr(groups))

    def test_peer_and_fence_timeline_keeps_decisions_without_identifiers(self):
        lines = [
            'wallMs=1 elapsedMs=1 <LogCollector:LidLease> passive_send cycle=1 type=ACQUIRE_REQUEST peer=AA:BB:CC:DD:EE:FF',
            'wallMs=2 elapsedMs=2 <LogCollector:LidLease> reservation_transition cycle=1 role=requester phase=IN_PROGRESS commandIssued=true',
            'wallMs=3 elapsedMs=3 <LogCollector:LidLease> audio_fence_persist cycle=1 success=true reason=AA:BB:CC:DD:EE:FF',
        ]
        events = analyze(lines, 'd')[0]['events']
        self.assertEqual('ACQUIRE_REQUEST', events[0]['type'])
        self.assertEqual('IN_PROGRESS', events[1]['phase'])
        self.assertEqual('true', events[2]['success'])
        self.assertNotIn('AA:BB:CC:DD:EE:FF', repr(events))

    def test_identifier_in_summary_intent_is_redacted(self):
        lines = ['wallMs=1 elapsedMs=1 <LogCollector:LidLease> event cycle=1 intentId=AA:BB:CC:DD:EE:FF']
        group = analyze(lines, 'd')[0]
        self.assertEqual(['[redacted]'], group['intents'])
        self.assertNotIn('AA:BB:CC:DD:EE:FF', repr(group))

    def test_overlapping_profile_reports_remain_separate_observations(self):
        fixture_dir = Path(__file__).parent / 'testdata'
        with (fixture_dir / 'overlapping-profile-reports.txt').open() as first:
            first_group = analyze(first, 'pixel')[0]
        with (fixture_dir / 'overlapping-peer-reports.txt').open() as second:
            second_group = analyze(second, 'xiaomi')[0]
        self.assertEqual('pixel', first_group['device'])
        self.assertEqual('xiaomi', second_group['device'])
        self.assertEqual(['2', '0'], [event['state'] for event in first_group['events']])
        self.assertEqual(['2', '0'], [event['state'] for event in second_group['events']])
        self.assertEqual('profile_disconnected_or_failed', first_group['result'])
        self.assertEqual('profile_disconnected_or_failed', second_group['result'])

    def test_accepted_dispatch_is_not_a_connection(self):
        lines = ['wallMs=1 elapsedMs=1 <LogCollector:LidLease> audio_dispatch_result cycle=1 result=accepted']
        self.assertEqual('unknown', analyze(lines, 'd')[0]['result'])


if __name__ == '__main__':
    unittest.main()
