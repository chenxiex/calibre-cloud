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



    def test_foreground_reads_global_window_focus_when_windows_subset_omits_it(self):
        adb = ui.Adb('offline')
        focused = b'  mCurrentFocus=Window{abc123 u0 test.app/test.app.MainActivity}\n'
        with patch.object(adb, 'shell', side_effect=lambda *args: focused if args == ('dumpsys', 'window') else b'Window list only') as shell:
            self.assertEqual('test.app', adb.foreground_package())
        shell.assert_called_once_with('dumpsys', 'window')

    def test_foreground_rejects_absent_or_ambiguous_focus(self):
        adb = ui.Adb('offline')
        focused = b'  mCurrentFocus=Window{abc123 u0 test.app/test.app.MainActivity}\n'
        for raw in (b'no focus', focused + focused, b'  mCurrentFocus=null\n'):
            with self.subTest(raw=raw), patch.object(adb, 'shell', return_value=raw):
                with self.assertRaisesRegex(ui.UiError, 'foreground_unavailable'):
                    adb.foreground_package()

    def fingerprint_with_outputs(self, input_dump, display_dump=b''):
        adb = ui.Adb('offline')
        outputs = {
            ('dumpsys', 'input'): input_dump,
            ('dumpsys', 'window', 'displays'): display_dump,
            ('getprop', 'ro.build.fingerprint'): b'fake-build',
            ('wm', 'size'): b'Physical size: 1080x1920',
            ('wm', 'density'): b'Physical density: 320',
            ('settings', 'get', 'system', 'font_scale'): b'1.0',
            ('settings', 'get', 'secure', 'navigation_mode'): b'2',
        }
        with patch.object(adb, 'shell', side_effect=lambda *args: outputs[args]):
            return adb.fingerprint()

    def test_fingerprint_keeps_surface_orientation_without_window_fallback(self):
        for rotation in range(4):
            with self.subTest(rotation=rotation):
                result = self.fingerprint_with_outputs(f'SurfaceOrientation: {rotation}\n'.encode())
                self.assertEqual(rotation, result['rotation'])
                self.assertEqual('fake-build', result['build_fingerprint'])

    def test_fingerprint_api34_uses_default_display_numeric_rotation(self):
        display = b"""WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0 (organized)
  DisplayRotation
    mRotation=0 mDeferredRotationPauseCount=0
    mOverrideConfig={mRotation=ROTATION_0}
    mFullConfiguration={mRotation=undefined}
"""
        result = self.fingerprint_with_outputs(b'Input Manager State without orientation', display)
        self.assertEqual(0, result['rotation'])

    def test_fingerprint_fallback_accepts_all_four_numeric_rotations(self):
        for rotation in range(4):
            with self.subTest(rotation=rotation):
                display = f'  Display: mDisplayId=0\n    mRotation={rotation} mDeferredRotationPauseCount=0\n'.encode()
                self.assertEqual(rotation, self.fingerprint_with_outputs(b'no orientation', display)['rotation'])

    def test_fingerprint_fallback_rejects_missing_invalid_and_configuration_rotations(self):
        for display in (
            b'  Display: mDisplayId=0\n    mOverrideConfig={mRotation=ROTATION_0}\n',
            b'  Display: mDisplayId=0\n    mRotation=undefined\n',
            b'  Display: mDisplayId=0\n    mRotation=4 mDeferredRotationPauseCount=0\n',
            b'    mRotation=0 mDeferredRotationPauseCount=0\n',
            b'  Display: mDisplayId=1\n    mRotation=0 mDeferredRotationPauseCount=0\n',
        ):
            with self.subTest(display=display), self.assertRaisesRegex(ui.UiError, 'rotation_unavailable'):
                self.fingerprint_with_outputs(b'no orientation', display)

    def test_fingerprint_fallback_rejects_ambiguous_numeric_rotation_or_default_display(self):
        for display in (
            b'  Display: mDisplayId=0\n    mRotation=0\n    mRotation=1\n',
            b'  Display: mDisplayId=0\n    mRotation=0\n    mRotation=0\n',
            b'  Display: mDisplayId=0\n    mRotation=0\n  Display: mDisplayId=1\n    mRotation=1\n',
            b'  Display: mDisplayId=0\n  Display: mDisplayId=0\n    mRotation=0\n',
            b'  Display: mDisplayId=0\n  Display: mDisplayId=1\n    mRotation=0\n',
        ):
            with self.subTest(display=display), self.assertRaisesRegex(ui.UiError, 'rotation_unavailable'):
                self.fingerprint_with_outputs(b'no orientation', display)

    def test_fingerprint_rejects_ambiguous_or_invalid_surface_orientation(self):
        for input_dump in (
            b'SurfaceOrientation: 0\nSurfaceOrientation: 1\n',
            b'SurfaceOrientation: 4\n',
            b'SurfaceOrientation: -1\n',
            b'SurfaceOrientation: invalid\n',
        ):
            with self.subTest(input_dump=input_dump), self.assertRaisesRegex(ui.UiError, 'rotation_unavailable'):
                self.fingerprint_with_outputs(input_dump)

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
