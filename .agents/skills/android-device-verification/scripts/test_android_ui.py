"""Offline regression tests; no device connection or source-library writes."""
import subprocess
import contextlib
import io
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import android_ui as ui


def screen(nodes):
    return ET.fromstring('<hierarchy>' + nodes + '</hierarchy>')


def node(text='go', enabled='true', clickable='true', inner='', bounds='[0,0][100,80]', package='test.app'):
    return f'<node package="{package}" text="{text}" enabled="{enabled}" clickable="{clickable}" bounds="{bounds}">{inner}</node>'


class FakeAdb:
    def __init__(self, screens):
        self.screens = list(screens)
        self.taps = []

    def foreground_package(self):
        return 'test.app'

    def dump(self, timeout=15):
        if len(self.screens) > 1:
            return self.screens.pop(0)
        return self.screens[0]

    def tap(self, x, y):
        self.taps.append((x, y))

    def fingerprint(self):
        return {'build_fingerprint': 'fake', 'wm_size': 'Physical size: 100x200',
                'wm_density': 'Physical density: 160', 'rotation': 0,
                'font_scale': '1.0', 'navigation_mode': '2'}


class UiTests(unittest.TestCase):

    def test_wait_observes_page_change(self):
        adb = FakeAdb([screen(node()), screen(node('done'))])
        with patch.object(ui.time, 'sleep'):
            self.assertEqual([0, 0, 100, 80], ui.wait_for(adb, {'text': 'done'})['bounds'])



    def test_remote_arguments_are_shell_quoted(self):
        adb = ui.Adb('serial; unsafe')
        with patch.object(adb, 'run', return_value=b'') as run:
            adb.shell('echo', 'a;$(touch /tmp/unwanted)')
        run.assert_called_once_with(['shell', "echo 'a;$(touch /tmp/unwanted)'"], 15)

    def test_local_arguments_do_not_use_shell(self):
        with patch.object(ui.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'', b'')) as run:
            ui.Adb('serial; unsafe').run(['version'])
        self.assertEqual(['adb', '-s', 'serial; unsafe', 'version'], run.call_args.args[0])
        self.assertNotIn('shell', run.call_args.kwargs)

    def test_lock_excludes_second_owner(self):
        with ui.device_lock('offline-test-device'):
            with self.assertRaisesRegex(ui.UiError, 'device_busy'):
                with ui.device_lock('offline-test-device'):
                    self.fail('second lock unexpectedly acquired')
        with ui.device_lock('offline-test-device'):
            pass

    def test_coordinates_require_matching_device(self):
        adb = FakeAdb([screen(node())])
        profile = {'device': {**adb.fingerprint(), 'rotation': 1},
                   'precondition': {'package': 'test.app', 'text': 'go'}, 'point': [50, 40]}
        with self.assertRaisesRegex(ui.UiError, 'device_profile_mismatch'):
            ui.coordinate(adb, profile, {'package': 'test.app', 'text': 'go'}, 1)
        self.assertEqual([], adb.taps)

    def test_coordinate_same_dimensions_changed_font_rejected(self):
        adb = FakeAdb([screen(node())])
        profile = {'device': {**adb.fingerprint(), 'font_scale': '1.2'},
                   'precondition': {'package': 'test.app', 'text': 'go'}, 'point': [50, 40]}
        with self.assertRaisesRegex(ui.UiError, 'device_profile_mismatch'):
            ui.coordinate(adb, profile, {'package': 'test.app', 'text': 'go'}, 1)
        self.assertEqual([], adb.taps)

    def test_coordinates_check_page_and_postcondition(self):
        adb = FakeAdb([screen(node()), screen(node()), screen(node('done'))])
        profile = {'device': adb.fingerprint(), 'precondition': {'package': 'test.app', 'text': 'go'}, 'point': [50, 40]}
        ui.coordinate(adb, profile, {'package': 'test.app', 'text': 'done'}, 1)
        self.assertEqual([(50, 40)], adb.taps)

    def test_artifacts_reject_home(self):
        with self.assertRaisesRegex(ui.UiError, 'outside_allowed'):
            ui.allowed_output('/home/unrelated/screen.xml')


    def test_non_object_flow_is_rejected(self):
        with self.assertRaisesRegex(ui.UiError, 'flow_requires_steps'):
            ui.run_flow(FakeAdb([]), [])

    def test_dump_reads_and_cleans_only_own_remote_file(self):
        adb = ui.Adb('offline')
        with patch.object(adb, 'shell', return_value=b'') as shell, patch.object(adb, 'run', return_value=b'<hierarchy/>') as run:
            adb.dump()
        remote = shell.call_args_list[0].args[2]
        self.assertRegex(remote, r'^/data/local/tmp/calibre-ui-[a-f0-9]{32}\.xml$')
        self.assertEqual(('rm', '-f', remote), shell.call_args_list[-1].args)
        self.assertEqual(['exec-out', 'cat', remote], run.call_args.args[0])

    def test_inspect_labels_are_opt_in_and_limit_applies(self):
        adb = FakeAdb([screen(node() + node())])
        result = ui.inspect_nodes(adb, {'text': 'go'}, limit=1)
        self.assertEqual(2, result['count'])
        self.assertTrue(result['truncated'])
        self.assertNotIn('text', result['nodes'][0])
        self.assertIn('resource_id', result['nodes'][0])
        labels = ui.inspect_nodes(adb, {'text': 'go'}, include_labels=True, limit=1)
        self.assertEqual('go', labels['nodes'][0]['text'])
        with self.assertRaises(ui.UiError):
            ui.inspect_nodes(adb, {'text': 'go'}, limit=21)

    def test_artifact_directory_symlink_cannot_escape_allowed_roots(self):
        with tempfile.TemporaryDirectory() as directory:
            link = Path(directory) / 'outside'
            link.symlink_to('/home')
            with self.assertRaisesRegex(ui.UiError, 'outside_allowed'):
                ui.allowed_output(link / 'capture.xml')

    def test_coordinate_types_are_validated(self):
        adb = FakeAdb([screen(node())])
        for point in ([True, 5], [1.2, 5], [-1, 5], [100, 5]):
            profile = {'device': adb.fingerprint(), 'precondition': {'package': 'test.app', 'text': 'go'}, 'point': point}
            with self.assertRaises(ui.UiError):
                ui.coordinate(adb, profile, {'package': 'test.app', 'text': 'go'}, 1)
        self.assertEqual([], adb.taps)



if __name__ == '__main__':
    unittest.main()
