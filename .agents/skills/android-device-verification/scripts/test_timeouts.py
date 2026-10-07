"""Timeout configuration, read recovery and diagnostic regressions; no device needed."""
import json
import subprocess
import unittest
from unittest.mock import patch

import android_ui as ui
from test_safety import FakeAdb, TARGET, PRE, POST, screen, node


class TimeoutTests(unittest.TestCase):
    def test_query_timeout_applies_to_dump_and_focus(self):
        adb = ui.Adb('offline', query_timeout=30, query_retries=0)
        outputs = [b'', b'<hierarchy/>', b'', b'  mCurrentFocus=Window{abc u0 test.app/.Main}\n']
        with patch.object(ui.subprocess, 'run', side_effect=[subprocess.CompletedProcess([], 0, out, b'') for out in outputs]) as run:
            adb.dump()
            adb.foreground_package()
        self.assertGreater(run.call_args_list[0].kwargs['timeout'], 15)
        self.assertEqual(30, run.call_args_list[-1].kwargs['timeout'])

    def test_retry_restarts_both_snapshots_before_tap(self):
        adb = FakeAdb([screen()])
        adb.query_retries = 1
        with patch.object(adb, 'dump', side_effect=[screen(), ui.UiError('adb_timeout'), screen(), screen(), screen(node('done'))]) as dump:
            ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual(5, dump.call_count)
        self.assertEqual([(50, 40)], adb.taps)

    def test_changed_page_on_retry_is_rejected(self):
        adb = FakeAdb([screen()])
        adb.query_retries = 1
        with patch.object(adb, 'dump', side_effect=[screen(), ui.UiError('adb_timeout'), screen(node('different'))]):
            with self.assertRaisesRegex(ui.UiError, 'selector_not_found'):
                ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual([], adb.taps)

    def test_tap_timeout_is_never_retried(self):
        adb = FakeAdb([screen()])
        adb.query_retries = 2
        with patch.object(adb, 'tap', side_effect=ui.UiError('adb_timeout')) as tap:
            with self.assertRaisesRegex(ui.UiError, 'adb_timeout'):
                ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual(1, tap.call_count)
        self.assertTrue(adb.action_may_have_executed)

    def test_postcondition_query_retry_does_not_repeat_tap(self):
        adb = FakeAdb([screen()])
        adb.query_retries = 1
        with patch.object(adb, 'dump', side_effect=[screen(), screen(), ui.UiError('adb_timeout'), screen(node('done'))]):
            ui.tap(adb, TARGET, POST, timeout=2, precondition=PRE)
        self.assertEqual([(50, 40)], adb.taps)
        self.assertFalse(adb.action_may_have_executed)

    def test_retry_exhaustion_is_bounded(self):
        adb = FakeAdb([screen()])
        adb.query_retries = 1
        with patch.object(adb, 'dump', side_effect=ui.UiError('adb_timeout')) as dump:
            with self.assertRaisesRegex(ui.UiError, 'adb_timeout'):
                ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual(2, dump.call_count)
        self.assertEqual([], adb.taps)

    def test_cleanup_failure_does_not_mask_primary_failure(self):
        adb = ui.Adb('offline')
        with patch.object(adb, 'shell', side_effect=[ui.UiError('adb_timeout'), ui.UiError('adb_command_failed')]):
            with self.assertRaisesRegex(ui.UiError, '^adb_timeout$'):
                adb.dump()
        self.assertEqual(['adb_command_failed'], adb.cleanup_errors)

    def test_successful_dump_survives_cleanup_timeout(self):
        adb = ui.Adb('offline')
        with patch.object(adb, 'shell', side_effect=[b'', ui.UiError('adb_timeout')]), patch.object(adb, 'run', return_value=b'<hierarchy/>'):
            self.assertEqual('hierarchy', adb.dump().tag)
        self.assertEqual(['adb_timeout'], adb.cleanup_errors)

    def test_diagnostics_include_stage_category_and_duration_without_secrets(self):
        adb = ui.Adb('offline')
        adb.stage = 'precheck'
        with patch.object(ui.subprocess, 'run', side_effect=subprocess.TimeoutExpired('SECRET_TOKEN', 15, output=b'PRIVATE_SCREEN')):
            with self.assertRaisesRegex(ui.UiError, 'adb_timeout'):
                adb.shell('uiautomator', 'dump', '/secret-path')
        report = adb.diagnostics[-1]
        self.assertEqual('ui_dump', report['command'])
        self.assertEqual('precheck', report['stage'])
        self.assertEqual('adb_timeout', report['status'])
        self.assertGreaterEqual(report['elapsed_seconds'], 0)
        self.assertNotIn('secret', json.dumps(report).lower())
        self.assertNotIn('PRIVATE_SCREEN', json.dumps(report))

    def test_invalid_query_options_rejected(self):
        for timeout in (True, 0, 121, float('nan'), float('inf')):
            with self.subTest(timeout=timeout), self.assertRaises(ui.UiError):
                ui.Adb('offline', query_timeout=timeout)
        for retries in (True, -1, 3, 1.5):
            with self.subTest(retries=retries), self.assertRaises(ui.UiError):
                ui.Adb('offline', query_retries=retries)

    def test_connection_error_is_not_retried(self):
        adb = FakeAdb([screen()])
        adb.query_retries = 2
        with patch.object(adb, 'dump', side_effect=ui.UiError('adb_command_failed')) as dump:
            with self.assertRaisesRegex(ui.UiError, 'adb_command_failed'):
                ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual(1, dump.call_count)
        self.assertEqual([], adb.taps)

    def test_coordinate_retry_rechecks_profile_and_page(self):
        adb = FakeAdb([screen()])
        adb.query_retries = 1
        profile = {'device': adb.fingerprint(), 'precondition': PRE, 'point': [50, 40]}
        with patch.object(adb, 'fingerprint', wraps=adb.fingerprint) as fingerprint, patch.object(
                adb, 'dump', side_effect=[screen(), ui.UiError('adb_timeout'), screen(), screen(), screen(node('done'))]):
            ui.coordinate(adb, profile, POST, 2)
        self.assertEqual(3, fingerprint.call_count)
        self.assertEqual([(50, 40)], adb.taps)

    def test_wait_decreasing_budget_includes_dump_read_and_focus(self):
        adb = ui.Adb('offline', query_timeout=30, query_retries=0)
        clock = [100.0]
        budgets = []

        def command(args, **kwargs):
            budgets.append((args[-1], kwargs['timeout']))
            clock[0] += .2
            if args[-1] == 'dumpsys window':
                raw = b'  mCurrentFocus=Window{abc u0 test.app/.Main}\n'
            elif args[3:5] == ['exec-out', 'cat']:
                raw = screen(node('done'))
                import xml.etree.ElementTree as ET
                raw = ET.tostring(raw)
            else:
                raw = b''
            return subprocess.CompletedProcess([], 0, raw, b'')

        with patch.object(ui.time, 'monotonic', side_effect=lambda: clock[0]), patch.object(ui.subprocess, 'run', side_effect=command):
            self.assertEqual([0, 0, 100, 80], ui.wait_for(adb, POST, timeout=2, expected_package='test.app')['bounds'])
        read_budgets = [budget for arg, budget in budgets if not arg.startswith('rm -f ')]
        self.assertEqual(4, len(read_budgets))
        for actual, expected in zip(read_budgets, (2, 1.8, 1.6, 1.2)):
            self.assertAlmostEqual(expected, actual)
        self.assertIsNone(adb.query_deadline)

    def test_expired_wait_reports_budget_and_keeps_underlying_timeout(self):
        adb = ui.Adb('offline', query_retries=1)
        clock = [100.0]

        def command(args, **kwargs):
            if args[-1].startswith('rm -f '):
                return subprocess.CompletedProcess([], 0, b'', b'')
            clock[0] += kwargs['timeout']
            raise subprocess.TimeoutExpired('PRIVATE', kwargs['timeout'])

        with patch.object(ui.time, 'monotonic', side_effect=lambda: clock[0]), patch.object(ui.subprocess, 'run', side_effect=command):
            with self.assertRaisesRegex(ui.UiError, '^postcondition_timeout$'):
                ui.wait_for(adb, POST, timeout=1)
        self.assertEqual('adb_timeout', adb.last_adb_failure['status'])
        self.assertEqual('ui_dump', adb.last_adb_failure['command'])
        self.assertEqual(0, adb.read_retries)
        self.assertIsNone(adb.query_deadline)

    def test_cli_persists_sanitized_timeout_diagnostics(self):
        import contextlib
        import io
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory, patch.object(ui.subprocess, 'run', side_effect=subprocess.TimeoutExpired('SECRET', 30)), contextlib.redirect_stdout(io.StringIO()) as output:
            with patch.object(ui.sys, 'argv', ['android_ui.py', '--serial', 'offline', '--query-retries', '0', '--artifacts', directory,
                                        'inspect', '--selector', json.dumps(TARGET)]):
                self.assertEqual(1, ui.main())
            report = json.loads(output.getvalue())
            saved = json.loads(next(Path(directory).glob('result-*.json')).read_text())
        self.assertEqual(report, saved)
        self.assertEqual('ui_dump', report['adb_diagnostics'][0]['command'])
        self.assertEqual('inspect', report['adb_diagnostics'][0]['stage'])
        self.assertFalse(report['action_may_have_executed'])
        self.assertNotIn('SECRET', json.dumps(report))

    def test_wait_query_budget_is_shared_and_not_reset_by_retry(self):
        adb = ui.Adb('offline', query_timeout=30, query_retries=1)
        with patch.object(ui.subprocess, 'run', side_effect=subprocess.TimeoutExpired('adb', 1)) as run:
            with self.assertRaises(ui.UiError):
                ui.wait_for(adb, POST, timeout=.05)
        self.assertTrue(all(call.kwargs['timeout'] <= .05 for call in run.call_args_list if not call.args[0][-1].startswith('rm -f ')))
        self.assertIsNone(adb.query_deadline)


if __name__ == '__main__':
    unittest.main()
