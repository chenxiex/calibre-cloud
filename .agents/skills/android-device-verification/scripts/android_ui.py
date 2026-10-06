#!/usr/bin/env python3
"""Small, deterministic ADB UI helper. JSON selectors use exact attribute values."""
import argparse
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[4]
ATTRIBUTES = {"resource_id": "resource-id", "text": "text", "content_desc": "content-desc", "package": "package"}


class UiError(Exception):
    pass


def validate_selector(selector):
    if not isinstance(selector, dict) or not any(k in selector for k in ATTRIBUTES):
        raise UiError("selector_requires_attribute")
    if set(selector) - set(ATTRIBUTES) - {"index"}:
        raise UiError("unknown_selector_attribute")
    if any(not isinstance(v, str) for k, v in selector.items() if k in ATTRIBUTES):
        raise UiError("selector_values_must_be_strings")
    if "index" in selector and (type(selector["index"]) is not int or selector["index"] < 0):
        raise UiError("index_must_be_nonnegative")
    return selector


def allowed_output(path):
    resolved = Path(path).resolve()
    roots = [PROJECT, Path('/tmp'), Path('/var/tmp')]
    if os.environ.get('TMPDIR'):
        roots.append(Path(os.environ['TMPDIR']).resolve())
    if not any(resolved == root or root in resolved.parents for root in roots):
        raise UiError("artifact_path_outside_allowed_roots")
    return resolved


@contextlib.contextmanager
def device_lock(serial):
    digest = hashlib.sha256(serial.encode()).hexdigest()
    # Shared across invocations and project checkouts; never delete a live lock inode.
    path = Path('/tmp') / ('calibre-android-ui-' + digest + '.lock')
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise UiError("device_busy") from exc
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


class Adb:
    def __init__(self, serial, artifacts=None, save_xml=False):
        self.serial = serial
        self.save_xml = save_xml
        self.artifacts = allowed_output(artifacts) if artifacts else None
        if self.artifacts:
            self.artifacts.mkdir(parents=True, exist_ok=True)
        self.counter = 0
        self.action_may_have_executed = False
        self.completed_steps = 0

    def run(self, args, timeout=15):
        try:
            return subprocess.run(['adb', '-s', self.serial, *args], stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, check=True, timeout=timeout).stdout
        except subprocess.TimeoutExpired as exc:
            raise UiError('adb_timeout') from exc
        except (subprocess.CalledProcessError, OSError) as exc:
            # ADB diagnostics can include paths, URLs or screen content.
            raise UiError('adb_command_failed') from exc

    def shell(self, *args, timeout=15):
        # adb shell performs an additional remote shell parse, even with argv locally.
        return self.run(['shell', shlex.join(str(a) for a in args)], timeout)

    def dump(self, timeout=15):
        remote = '/data/local/tmp/calibre-ui-' + uuid.uuid4().hex + '.xml'
        try:
            self.shell('uiautomator', 'dump', remote, timeout=timeout)
            raw = self.run(['exec-out', 'cat', remote], timeout=timeout)
            try:
                root = ET.fromstring(raw)
            except ET.ParseError as exc:
                raise UiError('invalid_ui_xml') from exc
            if self.artifacts and self.save_xml:
                self.counter += 1
                allowed_output(self.artifacts / f'ui-{self.counter:04d}-{uuid.uuid4().hex}.xml').write_bytes(raw)
            return root
        finally:
            self.shell('rm', '-f', remote, timeout=5)

    def foreground_package(self):
        raw = self.shell('dumpsys', 'window', 'windows').decode(errors='replace')
        lines = re.findall(r'^\s*mCurrentFocus=(.*)$', raw, re.MULTILINE)
        if len(lines) != 1:
            raise UiError('foreground_unavailable')
        match = re.fullmatch(r'Window\{[^{}\s]+\s+u\d+\s+([A-Za-z0-9_.]+)/[^{}\s]+\}', lines[0].strip())
        if not match:
            raise UiError('foreground_unavailable')
        return match.group(1)

    def tap(self, x, y):
        self.shell('input', 'tap', str(x), str(y))

    def screenshot(self):
        if not self.artifacts:
            raise UiError('screenshot_requires_artifacts')
        path = allowed_output(self.artifacts / ('screen-' + uuid.uuid4().hex + '.png'))
        path.write_bytes(self.run(['exec-out', 'screencap', '-p']))
        return str(path)

    def fingerprint(self):
        def value(*args):
            return self.shell(*args).decode().strip()
        rotation = value('dumpsys', 'input')
        match = re.search(r'SurfaceOrientation:\s*(\d+)', rotation)
        if not match:
            raise UiError('rotation_unavailable')
        return {'build_fingerprint': value('getprop', 'ro.build.fingerprint'),
                'wm_size': value('wm', 'size'), 'wm_density': value('wm', 'density'),
                'rotation': int(match.group(1)),
                'font_scale': value('settings', 'get', 'system', 'font_scale'),
                'navigation_mode': value('settings', 'get', 'secure', 'navigation_mode')}


def matches(root, selector):
    validate_selector(selector)
    return [node for node in root.iter('node') if all(node.get(attr) == selector[key]
            for key, attr in ATTRIBUTES.items() if key in selector)]


def bounds(node):
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not match:
        raise UiError('invalid_bounds')
    x1, y1, x2, y2 = map(int, match.groups())
    if x2 <= x1 or y2 <= y1 or node.get('visible-to-user') == 'false':
        raise UiError('node_not_visible')
    return [x1, y1, x2, y2]


def choose(root, selector, clickable=False):
    found = matches(root, selector)
    if 'index' in selector:
        if selector['index'] >= len(found):
            raise UiError('selector_not_found')
        node = found[selector['index']]
    else:
        if not found:
            raise UiError('selector_not_found')
        if len(found) != 1:
            raise UiError('selector_ambiguous')
        node = found[0]
    parents = {child: parent for parent in root.iter() for child in parent}
    target = node
    while True:
        if target.tag == 'node' and target.get('enabled') != 'true':
            raise UiError('node_disabled')
        bounds(target) if target.tag == 'node' else None
        if not clickable or target.get('clickable') == 'true':
            break
        target = parents.get(target)
        if target is None or target.tag != 'node':
            raise UiError('no_clickable_ancestor')
    # Check all ancestors, including disabled containers above a clickable target.
    ancestor = parents.get(target)
    while ancestor is not None:
        if ancestor.tag == 'node':
            if ancestor.get('enabled') != 'true':
                raise UiError('node_disabled')
            bounds(ancestor)
        ancestor = parents.get(ancestor)
    return target


def validate_timeout(timeout):
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not 0 < timeout <= 120:
        raise UiError('timeout_must_be_between_0_and_120')
    return timeout


def wait_for(adb, selector, timeout=10, poll=0.4, expected_package=None, transition_package=None):
    validate_selector(selector)
    deadline = time.monotonic() + validate_timeout(timeout)
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise UiError('postcondition_timeout')
        before = None
        allowed_packages = {expected_package, transition_package} - {None}
        if expected_package:
            before = adb.foreground_package()
            if before not in allowed_packages:
                raise UiError('foreground_package_mismatch')
        root = adb.dump(timeout=min(15, remaining))
        after = None
        if expected_package:
            after = adb.foreground_package()
            if after not in allowed_packages:
                raise UiError('foreground_package_mismatch')
        try:
            node = choose(root, selector)
            if not expected_package or before == after == expected_package:
                return {'bounds': bounds(node)}
        except UiError as exc:
            if str(exc) not in {'selector_not_found', 'node_disabled', 'node_not_visible'}:
                raise
        time.sleep(min(poll, max(0, deadline - time.monotonic())))


def validate_action_selector(selector):
    validate_selector(selector)
    if 'index' in selector:
        raise UiError('action_index_forbidden')
    if not selector.get('package', '').strip():
        raise UiError('action_package_required')
    if not any(selector.get(key, '').strip() for key in ('text', 'resource_id', 'content_desc')):
        raise UiError('action_page_attribute_required')
    return selector


def validate_tap_conditions(selector, precondition, postcondition):
    for condition in (selector, precondition, postcondition):
        validate_action_selector(condition)
    if precondition['package'] != selector['package']:
        raise UiError('precondition_package_mismatch')
    if precondition == selector:
        raise UiError('independent_precondition_required')


def check_focus(adb, package):
    if adb.foreground_package() != package:
        raise UiError('foreground_package_mismatch')


def signature(node):
    return tuple(sorted(node.attrib.items()))


def checked_snapshot(adb, package):
    check_focus(adb, package)
    root = adb.dump()
    check_focus(adb, package)
    return root


def action_target(root, selector):
    leaf = choose(root, selector)
    target = choose(root, selector, clickable=True)
    parents = {child: parent for parent in root.iter() for child in parent}
    lineage = []
    current = leaf
    while current is not None and current.tag == 'node':
        if current.get('package') != selector['package']:
            raise UiError('foreign_ancestor')
        lineage.append(current)
        current = parents.get(current)
    a, b = bounds(leaf), bounds(target)
    intersection = [max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])]
    if intersection[0] >= intersection[2] or intersection[1] >= intersection[3]:
        raise UiError('target_outside_clickable_ancestor')
    x, y = (intersection[0] + intersection[2]) // 2, (intersection[1] + intersection[3]) // 2
    for other in root.iter('node'):
        if other in lineage:
            continue
        # Reject any overlapping interactive/foreign node, regardless of tree ordering.
        if other.get('clickable') != 'true' and other.get('package') == selector['package']:
            continue
        box = bounds(other)
        if box[0] <= x < box[2] and box[1] <= y < box[3]:
            raise UiError('target_point_obstructed')
    return (x, y), tuple(signature(n) for n in lineage), leaf


def tap(adb, selector, postcondition=None, timeout=10, precondition=None):
    validate_tap_conditions(selector, precondition, postcondition)
    validate_timeout(timeout)
    first = checked_snapshot(adb, selector['package'])
    pre = choose(first, precondition)
    point, target_signature, leaf = action_target(first, selector)
    if pre is leaf:
        raise UiError('independent_precondition_required')
    second = checked_snapshot(adb, selector['package'])
    latest_pre = choose(second, precondition)
    latest_point, latest_signature, latest_leaf = action_target(second, selector)
    if latest_pre is latest_leaf:
        raise UiError('independent_precondition_required')
    if signature(pre) != signature(latest_pre) or (point, target_signature) != (latest_point, latest_signature):
        raise UiError('page_changed_before_tap')
    check_focus(adb, selector['package'])
    adb.action_may_have_executed = True
    adb.tap(*point)
    wait_for(adb, postcondition, timeout, expected_package=postcondition['package'], transition_package=selector['package'])
    adb.action_may_have_executed = False
    return {'tapped': True, 'postcondition_checked': True}


def validate_profile(profile):
    if not isinstance(profile, dict) or set(profile) != {'device', 'precondition', 'point'}:
        raise UiError('invalid_coordinate_profile')
    validate_action_selector(profile.get('precondition'))
    expected = profile.get('device')
    if not isinstance(expected, dict) or set(expected) != {'build_fingerprint', 'wm_size', 'wm_density', 'rotation', 'font_scale', 'navigation_mode'}:
        raise UiError('invalid_device_profile')
    if any(not isinstance(expected[k], str) or not expected[k]
           for k in ('build_fingerprint', 'wm_size', 'wm_density', 'font_scale', 'navigation_mode')):
        raise UiError('invalid_device_profile')
    if type(expected['rotation']) is not int or expected['rotation'] not in (0, 1, 2, 3):
        raise UiError('invalid_device_rotation')
    point = profile.get('point')
    if not isinstance(point, list) or len(point) != 2 or any(type(v) is not int or v < 0 for v in point):
        raise UiError('invalid_coordinate')
    sizes = re.findall(r'(\d+)x(\d+)', expected['wm_size'])
    if not sizes:
        raise UiError('invalid_device_size')
    width, height = map(int, sizes[-1])
    if expected['rotation'] in (1, 3):
        width, height = height, width
    if point[0] >= width or point[1] >= height:
        raise UiError('coordinate_outside_screen')
    return expected, point


def coordinate(adb, profile, postcondition, timeout):
    expected, point = validate_profile(profile)
    validate_action_selector(postcondition)
    validate_timeout(timeout)
    if adb.fingerprint() != expected:
        raise UiError('device_profile_mismatch')
    package = profile['precondition']['package']
    first = choose(checked_snapshot(adb, package), profile['precondition'])
    second = choose(checked_snapshot(adb, package), profile['precondition'])
    if signature(first) != signature(second):
        raise UiError('page_changed_before_tap')
    if adb.fingerprint() != expected:
        raise UiError('device_profile_mismatch')
    check_focus(adb, package)
    adb.action_may_have_executed = True
    adb.tap(*point)
    wait_for(adb, postcondition, timeout, expected_package=postcondition['package'], transition_package=package)
    adb.action_may_have_executed = False
    return {'tapped': True, 'postcondition_checked': True}


def run_flow(adb, flow, elements_only=False):
    adb.completed_steps = 0
    if not isinstance(flow, dict):
        raise UiError('flow_requires_steps')
    steps = flow.get('steps')
    if set(flow) != {'steps'} or not isinstance(steps, list) or not steps:
        raise UiError('flow_requires_steps')
    # Validate the entire flow before the first mutation.
    for step in steps:
        if not isinstance(step, dict) or step.get('action') not in {'tap', 'wait', 'coordinate'}:
            raise UiError('invalid_flow_action')
        if elements_only and step['action'] == 'coordinate':
            raise UiError('coordinate_forbidden_in_elements_only_flow')
        expected_keys = {'action', 'timeout', 'selector'} if step['action'] == 'wait' else (
            {'action', 'timeout', 'selector', 'precondition', 'postcondition'} if step['action'] == 'tap' else
            {'action', 'timeout', 'profile', 'postcondition'})
        if set(step) - expected_keys:
            raise UiError('unknown_flow_field')
        validate_timeout(step.get('timeout', 10))
        if step['action'] in {'tap', 'wait'}:
            validate_selector(step.get('selector'))
        if step['action'] == 'tap':
            validate_tap_conditions(step.get('selector'), step.get('precondition'), step.get('postcondition'))
        if step['action'] == 'coordinate':
            validate_action_selector(step.get('postcondition'))
        if step['action'] == 'coordinate':
            validate_profile(step.get('profile'))
    completed = 0
    for step in steps:
        try:
            if step['action'] == 'wait':
                wait_for(adb, step['selector'], step.get('timeout', 10))
            elif step['action'] == 'tap':
                tap(adb, step['selector'], step['postcondition'], step.get('timeout', 10), step['precondition'])
            else:
                coordinate(adb, step['profile'], step['postcondition'], step.get('timeout', 10))
        except UiError as exc:
            raise UiError(f'flow_step_{completed + 1}:{exc}') from exc
        completed += 1
        adb.completed_steps = completed
    return {'completed_steps': completed}


def inspect_nodes(adb, selector, include_labels=False, limit=20):
    if type(limit) is not int or not 1 <= limit <= 20:
        raise UiError('inspect_limit_must_be_between_1_and_20')
    found = matches(adb.dump(), selector)
    result = []
    for node in found[:limit]:
        detail = {'resource_id': node.get('resource-id', ''), 'package': node.get('package', ''),
                  'enabled': node.get('enabled') == 'true', 'clickable': node.get('clickable') == 'true',
                  'bounds': node.get('bounds')}
        if include_labels:
            detail.update(text=node.get('text', ''), content_desc=node.get('content-desc', ''))
        result.append(detail)
    return {'count': len(found), 'nodes': result, 'truncated': len(found) > limit}


def save_result(adb, result):
    if adb is not None and adb.artifacts:
        path = allowed_output(adb.artifacts / ('result-' + uuid.uuid4().hex + '.json'))
        path.write_text(json.dumps(result, ensure_ascii=False) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--artifacts')
    parser.add_argument('--save-xml', action='store_true', help='explicitly retain full raw UI XML; requires artifacts and authorized page capture')
    parser.add_argument('--screenshot', action='store_true', help='explicitly save one screenshot after operation')
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('inspect', 'tap', 'wait'):
        command = sub.add_parser(name)
        command.add_argument('--selector', required=True)
        if name == 'inspect':
            command.add_argument('--include-labels', action='store_true', help='explicitly include matching text/description; never use on authentication screens')
            command.add_argument('--limit', type=int, default=20)
        if name != 'inspect':
            command.add_argument('--timeout', type=float, default=10)
        if name == 'tap':
            command.add_argument('--postcondition', required=True)
            command.add_argument('--precondition', required=True)
    flow_command = sub.add_parser('flow')
    flow_command.add_argument('--file', required=True)
    flow_command.add_argument('--elements-only', action='store_true')
    sub.add_parser('device-profile')
    args = parser.parse_args()
    artifact_path = None
    adb = None
    try:
        if args.save_xml and not args.artifacts:
            raise UiError('save_xml_requires_artifacts')
        if args.screenshot and not args.artifacts:
            raise UiError('screenshot_requires_artifacts')
        adb = Adb(args.serial, args.artifacts, args.save_xml)
        artifact_path = str(adb.artifacts) if adb.artifacts else None
        with device_lock(args.serial):
            if args.command == 'flow':
                result = run_flow(adb, json.loads(Path(args.file).read_text()), args.elements_only)
            elif args.command == 'device-profile':
                result = adb.fingerprint()
            else:
                selector = validate_selector(json.loads(args.selector))
                if args.command == 'inspect':
                    result = inspect_nodes(adb, selector, args.include_labels, args.limit)
                elif args.command == 'wait':
                    result = wait_for(adb, selector, args.timeout)
                else:
                    post = json.loads(args.postcondition)
                    result = tap(adb, selector, post, args.timeout, json.loads(args.precondition))
            if args.screenshot:
                result['screenshot'] = adb.screenshot()
        if artifact_path:
            result['artifacts'] = artifact_path
        result = {'ok': True, **result}
        save_result(adb, result)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (UiError, ValueError, OSError) as exc:
        error = str(exc) if isinstance(exc, UiError) else 'invalid_input_or_file'
        result = {'ok': False, 'error': error,
                  'action_may_have_executed': bool(getattr(adb, 'action_may_have_executed', False)),
                  'completed_steps': getattr(adb, 'completed_steps', 0)}
        if artifact_path:
            result['artifacts'] = artifact_path
        try:
            save_result(adb, result)
        except (UiError, OSError):
            result['result_artifact_error'] = True
        print(json.dumps(result))
        return 1


if __name__ == '__main__':
    sys.exit(main())
