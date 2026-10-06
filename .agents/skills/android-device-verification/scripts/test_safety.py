"""Offline safety regressions: forbidden actions never reach input tap."""
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import android_ui as ui

TARGET = {'package': 'test.app', 'text': 'go'}
PRE = {'package': 'test.app', 'text': 'page'}
POST = {'package': 'test.app', 'text': 'done'}


def node(text='go', bounds='[0,0][100,80]', clickable='true', enabled='true', package='test.app', inner=''):
    return f'<node package="{package}" text="{text}" bounds="{bounds}" clickable="{clickable}" enabled="{enabled}">{inner}</node>'


def screen(content=None):
    return ET.fromstring('<hierarchy>' + (content if content is not None else node()) + node('page', '[0,90][100,120]', 'false') + '</hierarchy>')


class FakeAdb:
    def __init__(self, screens):
        self.screens = list(screens)
        self.taps = []
        self.focus = 'test.app'
        self.action_may_have_executed = False
        self.completed_steps = 0

    def fingerprint(self):
        return {'build_fingerprint': 'fake', 'wm_size': 'Physical size: 100x200',
                'wm_density': 'Physical density: 160', 'rotation': 0,
                'font_scale': '1.0', 'navigation_mode': '2'}

    def foreground_package(self):
        return self.focus

    def dump(self, timeout=15):
        return self.screens.pop(0) if len(self.screens) > 1 else self.screens[0]

    def tap(self, *point):
        self.taps.append(point)


class SafetyTests(unittest.TestCase):
    def test_missing_guards_rejected(self):
        for target, pre, post in (({'text': 'go'}, PRE, POST), (TARGET, None, POST),
                (TARGET, PRE, None), ({**TARGET, 'index': 0}, PRE, POST),
                (TARGET, TARGET, POST), (TARGET, {'package': 'test.app'}, POST)):
            adb = FakeAdb([screen()])
            with self.assertRaises(ui.UiError):
                ui.tap(adb, target, post, precondition=pre)
            self.assertEqual([], adb.taps)

    def test_changing_target_rejected(self):
        adb = FakeAdb([screen(), screen(node(bounds='[20,0][120,80]'))])
        with self.assertRaisesRegex(ui.UiError, 'changed'):
            ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual([], adb.taps)

    def test_wrong_focus_rejected(self):
        adb = FakeAdb([screen()])
        adb.focus = 'personal.app'
        with self.assertRaisesRegex(ui.UiError, 'foreground'):
            ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual([], adb.taps)

    def test_overlapping_and_disabled_and_foreign_ancestor_rejected(self):
        for content in (node() + node('overlay'), node() + node(), node(enabled='false'),
                node(bounds='[0,0][0,80]'), node().replace('enabled=', 'visible-to-user="false" enabled='),
                node('parent', package='personal.app', inner=node()),
                node('parent', enabled='false', inner=node())):
            adb = FakeAdb([screen(content)])
            with self.assertRaises(ui.UiError):
                ui.tap(adb, TARGET, POST, precondition=PRE)
            self.assertEqual([], adb.taps)

    def test_leaf_center_used(self):
        before = screen(node('parent', inner=node(bounds='[0,0][20,20]', clickable='false')))
        adb = FakeAdb([before, before, screen(node('done'))])
        ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual([(10, 10)], adb.taps)

    def test_focus_parser_fails_closed(self):
        adb = ui.Adb('offline')
        with patch.object(adb, 'shell', return_value=b'mCurrentFocus=Window{123 u0 test.app/.Main}'):
            self.assertEqual('test.app', adb.foreground_package())
        for raw in (b'mCurrentFocus=null', b'', b'mCurrentFocus=Window{123 u0 test.app/.Main}\nmCurrentFocus=Window{456 u0 other.app/.Main}'):
            with patch.object(adb, 'shell', return_value=raw), self.assertRaises(ui.UiError):
                adb.foreground_package()

    def test_entire_flow_validated_before_mutation(self):
        adb = FakeAdb([screen()])
        good = {'action': 'tap', 'selector': TARGET, 'precondition': PRE, 'postcondition': POST}
        for bad in ({'action': 'tap', 'selector': TARGET, 'postcondition': POST},
                {**good, 'selector': {**TARGET, 'index': 0}},
                {**good, 'precondition': TARGET},
                {'action': 'coordinate', 'profile': {'device': {}, 'point': [0, 0], 'precondition': PRE}, 'postcondition': POST}):
            with self.assertRaises(ui.UiError):
                ui.run_flow(adb, {'steps': [good, bad]})
            self.assertEqual([], adb.taps)

    def test_actual_node_alias_rejected(self):
        root = screen()
        list(root)[0].set('resource-id', 'test.app:id/go')
        adb = FakeAdb([root])
        with self.assertRaisesRegex(ui.UiError, 'independent_precondition'):
            ui.tap(adb, TARGET, POST, precondition={'package': 'test.app', 'text': 'go', 'resource_id': 'test.app:id/go'})
        self.assertEqual([], adb.taps)

    def test_page_marker_change_rejected(self):
        before = screen()
        after = screen()
        list(after)[1].set('bounds', '[0,100][100,130]')
        adb = FakeAdb([before, after])
        with self.assertRaisesRegex(ui.UiError, 'changed'):
            ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual([], adb.taps)

    def test_focus_change_during_snapshot_rejected(self):
        adb = FakeAdb([screen()])
        with patch.object(adb, 'foreground_package', side_effect=['test.app', 'personal.app']):
            with self.assertRaisesRegex(ui.UiError, 'foreground'):
                ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertEqual([], adb.taps)

    def test_postcondition_timeout_stops_flow(self):
        adb = FakeAdb([screen()])
        good = {'action': 'tap', 'selector': TARGET, 'precondition': PRE, 'postcondition': POST, 'timeout': .001}
        with self.assertRaisesRegex(ui.UiError, 'flow_step_1:postcondition_timeout'):
            ui.run_flow(adb, {'steps': [good, good]})
        self.assertEqual([(50, 40)], adb.taps)

    def test_cli_requires_pre_and_post_before_adb(self):
        for flags in ([], ['--precondition', '{}'], ['--postcondition', '{}']):
            with patch.object(ui.sys, 'argv', ['android_ui.py', '--serial', 'offline', 'tap', '--selector', '{}', *flags]), patch.object(ui, 'Adb') as adapter, patch('sys.stderr'):
                with self.assertRaises(SystemExit):
                    ui.main()
                adapter.assert_not_called()

    def test_foreign_precondition_rejected(self):
        adb = FakeAdb([screen()])
        with self.assertRaisesRegex(ui.UiError, 'precondition_package'):
            ui.tap(adb, TARGET, POST, precondition={**PRE, 'package': 'personal.app'})
        self.assertEqual([], adb.taps)

    def test_cli_guarded_tap_reports_artifact_path(self):
        import contextlib
        import io
        import json
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            adapter = FakeAdb([screen(), screen(), screen(node('done'))])
            adapter.artifacts = ui.allowed_output(directory)
            output = io.StringIO()
            with patch.object(ui.sys, 'argv', ['android_ui.py', '--serial', 'offline-guard-test', '--artifacts', directory,
                    'tap', '--selector', json.dumps(TARGET), '--precondition', json.dumps(PRE), '--postcondition', json.dumps(POST)]), \
                    patch.object(ui, 'Adb', return_value=adapter), contextlib.redirect_stdout(output):
                self.assertEqual(0, ui.main())
            result = json.loads(output.getvalue())
            self.assertTrue(result['postcondition_checked'])
            self.assertEqual(directory, result['artifacts'])
            self.assertEqual([(50, 40)], adapter.taps)

    def test_coordinate_changed_page_never_clicks(self):
        before, after = screen(), screen()
        list(after)[1].set('bounds', '[0,100][100,130]')
        adb = FakeAdb([before, after])
        profile = {'device': adb.fingerprint(), 'precondition': PRE, 'point': [50, 40]}
        with self.assertRaisesRegex(ui.UiError, 'changed'):
            ui.coordinate(adb, profile, POST, 1)
        self.assertEqual([], adb.taps)

    def test_coordinate_changed_device_never_clicks(self):
        adb = FakeAdb([screen(), screen()])
        original = adb.fingerprint()
        profile = {'device': original, 'precondition': PRE, 'point': [50, 40]}
        with patch.object(adb, 'fingerprint', side_effect=[original, {**original, 'rotation': 1}]):
            with self.assertRaisesRegex(ui.UiError, 'device_profile_mismatch'):
                ui.coordinate(adb, profile, POST, 1)
        self.assertEqual([], adb.taps)

    def test_coordinate_guards_prevalidated_for_entire_flow(self):
        adb = FakeAdb([screen()])
        good = {'action': 'tap', 'selector': TARGET, 'precondition': PRE, 'postcondition': POST}
        profile = {'device': adb.fingerprint(), 'precondition': {**PRE, 'index': 0}, 'point': [50, 40]}
        with self.assertRaisesRegex(ui.UiError, 'index_forbidden'):
            ui.run_flow(adb, {'steps': [good, {'action': 'coordinate', 'profile': profile, 'postcondition': POST}]})
        self.assertEqual([], adb.taps)

    def test_possible_action_marker_includes_transport_failure(self):
        adb = FakeAdb([screen(), screen()])
        with patch.object(adb, 'tap', side_effect=ui.UiError('adb_timeout')):
            with self.assertRaisesRegex(ui.UiError, 'adb_timeout'):
                ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertTrue(adb.action_may_have_executed)

    def test_action_marker_and_completed_steps_after_post_timeout(self):
        adb = FakeAdb([screen()])
        wait = {'action': 'wait', 'selector': PRE}
        tap = {'action': 'tap', 'selector': TARGET, 'precondition': PRE, 'postcondition': POST, 'timeout': .001}
        with self.assertRaisesRegex(ui.UiError, 'flow_step_2:postcondition_timeout'):
            ui.run_flow(adb, {'steps': [wait, tap]})
        self.assertEqual(1, adb.completed_steps)
        self.assertTrue(adb.action_may_have_executed)

    def test_success_clears_possible_action_marker(self):
        adb = FakeAdb([screen(), screen(), screen(node('done'))])
        ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertFalse(adb.action_may_have_executed)

    def test_cli_failure_reports_possible_action(self):
        import contextlib
        import io
        import json
        for pre, expected in ((PRE, True), (TARGET, False)):
            adapter = FakeAdb([screen()])
            adapter.artifacts = None
            output = io.StringIO()
            with patch.object(ui.sys, 'argv', ['android_ui.py', '--serial', 'offline-failure-test',
                    'tap', '--selector', json.dumps(TARGET), '--precondition', json.dumps(pre),
                    '--postcondition', json.dumps(POST), '--timeout', '.001']), \
                    patch.object(ui, 'Adb', return_value=adapter), contextlib.redirect_stdout(output):
                self.assertEqual(1, ui.main())
            result = json.loads(output.getvalue())
            self.assertEqual(expected, result['action_may_have_executed'])
            self.assertEqual(0, result['completed_steps'])

    def test_elements_only_flow_rejects_later_coordinate_before_first_tap(self):
        adb = FakeAdb([screen()])
        good = {'action': 'tap', 'selector': TARGET, 'precondition': PRE, 'postcondition': POST}
        profile = {'device': adb.fingerprint(), 'precondition': PRE, 'point': [50, 40]}
        with self.assertRaisesRegex(ui.UiError, 'coordinate_forbidden_in_elements_only_flow'):
            ui.run_flow(adb, {'steps': [good, {'action': 'coordinate', 'profile': profile, 'postcondition': POST}]}, elements_only=True)
        self.assertEqual([], adb.taps)
        self.assertFalse(adb.action_may_have_executed)
        self.assertEqual(0, adb.completed_steps)

    def test_postcondition_with_foreign_focus_never_reports_success(self):
        adb = FakeAdb([screen(), screen(), screen(node('done'))])
        with patch.object(adb, 'foreground_package', side_effect=['test.app'] * 5 + ['personal.app']):
            with self.assertRaisesRegex(ui.UiError, 'foreground'):
                ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertTrue(adb.action_may_have_executed)
        self.assertEqual([(50, 40)], adb.taps)

    def test_postcondition_focus_changes_during_dump_never_reports_success(self):
        adb = FakeAdb([screen(), screen(), screen(node('done'))])
        with patch.object(adb, 'foreground_package', side_effect=['test.app'] * 6 + ['personal.app']):
            with self.assertRaisesRegex(ui.UiError, 'foreground'):
                ui.tap(adb, TARGET, POST, precondition=PRE)
        self.assertTrue(adb.action_may_have_executed)

    def test_postcondition_unknown_focus_stops_flow(self):
        adb = FakeAdb([screen(), screen(), screen(node('done'))])
        good = {'action': 'tap', 'selector': TARGET, 'precondition': PRE, 'postcondition': POST}
        with patch.object(adb, 'foreground_package', side_effect=['test.app'] * 5 + [ui.UiError('foreground_unavailable')]):
            with self.assertRaisesRegex(ui.UiError, 'flow_step_1:foreground_unavailable'):
                ui.run_flow(adb, {'steps': [good, good]})
        self.assertTrue(adb.action_may_have_executed)
        self.assertEqual(0, adb.completed_steps)
        self.assertEqual([(50, 40)], adb.taps)

    def test_raw_xml_artifact_requires_explicit_opt_in(self):
        import tempfile
        from pathlib import Path
        for save in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                adb = ui.Adb('offline', directory, save_xml=save)
                with patch.object(adb, 'shell', return_value=b''), patch.object(adb, 'run', return_value=b'<hierarchy/>'):
                    adb.dump()
                self.assertEqual(int(save), len(list(Path(directory).glob('*.xml'))))

    def test_postcondition_allows_known_package_transition_without_accepting_inconsistent_sample(self):
        post_root = screen(node('done', package='system.picker'))
        adb = FakeAdb([post_root, post_root, post_root])
        post = {'package': 'system.picker', 'text': 'done'}
        with patch.object(adb, 'foreground_package', side_effect=[
                'test.app', 'test.app', 'test.app', 'system.picker', 'system.picker', 'system.picker']), patch.object(ui.time, 'sleep'):
            self.assertEqual([0, 0, 100, 80], ui.wait_for(adb, post, expected_package='system.picker', transition_package='test.app')['bounds'])
        self.assertEqual([], adb.screens[1:])

    def test_raw_xml_and_short_result_artifacts_are_separate(self):
        import tempfile
        import json
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            adb = ui.Adb('offline', directory)
            with patch.object(adb, 'shell', return_value=b''), patch.object(adb, 'run', return_value=b'<hierarchy><node text="private-screen-data"/></hierarchy>'):
                adb.dump()
            ui.save_result(adb, {'ok': False, 'error': 'foreground_unavailable', 'action_may_have_executed': True, 'completed_steps': 0})
            files = list(Path(directory).iterdir())
            self.assertEqual(1, len(files))
            self.assertEqual('.json', files[0].suffix)
            self.assertNotIn('private-screen-data', files[0].read_text())
            self.assertTrue(json.loads(files[0].read_text())['action_may_have_executed'])

    def test_cli_failure_retains_short_result_artifact(self):
        import contextlib
        import io
        import json
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as directory:
            adapter = FakeAdb([screen()])
            adapter.artifacts = ui.allowed_output(directory)
            output = io.StringIO()
            with patch.object(ui.sys, 'argv', ['android_ui.py', '--serial', 'offline-result-test', '--artifacts', directory,
                    'tap', '--selector', json.dumps(TARGET), '--precondition', json.dumps(TARGET), '--postcondition', json.dumps(POST)]), \
                    patch.object(ui, 'Adb', return_value=adapter), contextlib.redirect_stdout(output):
                self.assertEqual(1, ui.main())
            results = list(Path(directory).glob('result-*.json'))
            self.assertEqual(1, len(results))
            self.assertEqual(json.loads(output.getvalue()), json.loads(results[0].read_text()))
