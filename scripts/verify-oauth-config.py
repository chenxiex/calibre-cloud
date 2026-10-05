#!/usr/bin/env python3
"""Build isolated synthetic OAuth configurations and inspect both shipped variants.

Run from any directory with Python 3. Only sdk.dir is copied from the user's
local.properties. Reports and disposable project copies stay in the ignored
.oauth-verification directory; no registered client or real callback is needed.
"""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".oauth-verification"
ANDROID = "{http://schemas.android.com/apk/res/android}"
RECEIVER = "net.openid.appauth.RedirectUriReceiverActivity"
CLIENT = "onedrive.clientId"
RELEASE = "onedrive.redirectUri"
DEBUG = "onedrive.debugRedirectUri"
BASE_ID = "io.github.chenxiex.calibrecloud"


def properties_decode(value):
    def replace(match):
        token = match.group(1)
        if token.startswith("u"):
            return chr(int(token[1:], 16))
        return {"t": "\t", "r": "\r", "n": "\n", "f": "\f"}.get(token, token)

    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", replace, value)


def sdk_directory():
    source = ROOT / "local.properties"
    if source.exists():
        for line in source.read_text(encoding="utf-8").splitlines():
            match = re.match(r"\s*sdk\.dir\s*[=:]\s*(.*)$", line)
            if match:
                return Path(properties_decode(match.group(1)))
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(variable):
            return Path(os.environ[variable])
    raise RuntimeError("Set sdk.dir in local.properties or ANDROID_HOME to run APK inspection")


def properties_encode(value):
    return (value.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t").replace(" ", "\\ "))


def fixtures():
    valid = {
        CLIENT: "synthetic-matrix-client",
        RELEASE: "calibre-matrix-release://oauth/return",
        DEBUG: "calibre-matrix-debug://oauth/debug-return",
    }
    return [
        ("missing", {}, None),
        ("partial-client", {CLIENT: valid[CLIENT]}, None),
        ("partial-callback", {CLIENT: valid[CLIENT], RELEASE: valid[RELEASE]}, None),
        ("host-a", valid, None),
        ("host-b", {CLIENT: "synthetic-matrix-second", RELEASE: "other-release://signin/callback", DEBUG: "other-debug://signin/callback"}, None),
        ("no-host", {**valid, RELEASE: "calibre-release:/oauth2redirect", DEBUG: "calibre-debug:/oauth2redirect"}, None),
        ("same-scheme-hosts", {**valid, RELEASE: "calibre-shared://release/return", DEBUG: "calibre-shared://debug/return"}, None),
        ("same-scheme-paths", {**valid, RELEASE: "calibre-shared://oauth/release", DEBUG: "calibre-shared://oauth/debug"}, None),
        ("safe-client-string", {**valid, CLIENT: 'synthetic-"quoted"-\\client'}, None),
        ("invalid-scheme", {**valid, RELEASE: "1invalid://oauth/return"}, RELEASE),
        ("credentials", {**valid, DEBUG: "calibre-debug://fixture-user:fixture-password@oauth/return"}, DEBUG),
        ("overlap", {**valid, RELEASE: "calibre-shared://oauth/return", DEBUG: "calibre-shared://oauth/return"}, RELEASE),
        ("no-host-overlap", {**valid, RELEASE: "calibre-shared:/release", DEBUG: "calibre-shared:/debug"}, RELEASE),
    ]


def sanitized(text, values, sdk):
    for value in sorted(values.values(), key=len, reverse=True):
        if value:
            text = text.replace(value, "<synthetic-value>")
    for value in (str(WORK), str(ROOT), str(sdk), str(Path.home()), "fixture-password"):
        text = text.replace(value, "<path-or-fixture>")
    return text


def run(command, project):
    result = subprocess.run(command, cwd=project, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False)
    return result.returncode, result.stdout


def copy_project(destination):
    # Exclude generated/local data everywhere, but preserve Wrapper and lockfiles.
    excluded = {".git", ".gradle", ".kotlin", "build", "local.properties", ".oauth-verification", "__pycache__"}

    def ignore(directory, names):
        ignored = [name for name in names if name in excluded]
        # Do not follow links into unrelated directories or copy private material.
        for name in names:
            path = Path(directory) / name
            if path.is_symlink():
                ignored.append(name)
            if name.endswith((".jks", ".keystore")) or name == ".env" or name.startswith(".env.") and name != ".env.example":
                ignored.append(name)
        return ignored

    shutil.copytree(ROOT, destination, ignore=ignore)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def build_config(project, variant):
    candidates = list((project / "app/build/generated").rglob("BuildConfig.java"))
    candidates = [path for path in candidates if variant in path.parts]
    require(len(candidates) == 1, f"{variant}: expected exactly one generated BuildConfig")
    source = candidates[0].read_text(encoding="utf-8")
    fields = {}
    for field in ("ONEDRIVE_CONFIGURED", "ONEDRIVE_CLIENT_ID", "ONEDRIVE_REDIRECT_URI"):
        match = re.search(rf"\b{field}\s*=\s*(.*?);", source)
        require(match is not None, f"{variant}: missing BuildConfig.{field}")
        fields[field] = json.loads(match.group(1))
    return fields


def check_manifest(xml, variant, values, configured):
    manifest = ET.fromstring(xml)
    package = BASE_ID + (".debug" if variant == "debug" else "")
    require(manifest.get("package") == package, f"{variant}: incorrect application ID")
    application = manifest.find("application")
    receivers = [item for item in application.findall("activity") if item.get(ANDROID + "name") == RECEIVER]
    require(len(receivers) == 1, f"{variant}: expected one AppAuth callback receiver")
    receiver = receivers[0]
    require(receiver.get(ANDROID + "enabled", "true") == str(configured).lower(), f"{variant}: callback enablement mismatch")
    data = receiver.findall("intent-filter/data")
    schemes = {item.get(ANDROID + "scheme") for item in data if item.get(ANDROID + "scheme")}
    if not configured:
        require(schemes == {package + ".disabled"}, f"{variant}: missing isolated disabled scheme")
        return
    uri = urlsplit(values[DEBUG if variant == "debug" else RELEASE])
    require(receiver.get(ANDROID + "exported") == "true", f"{variant}: configured callback is not exported")
    require(schemes == {uri.scheme}, f"{variant}: callback scheme mismatch")
    if not uri.hostname:
        require(all(ANDROID + "host" not in item.attrib and ANDROID + "path" not in item.attrib for item in data), f"{variant}: hostless callback contains empty authority/path constraints")
    hosts = {item.get(ANDROID + "host") for item in data if item.get(ANDROID + "host")}
    paths = {item.get(ANDROID + "path") for item in data if item.get(ANDROID + "path")}
    require(hosts == ({uri.hostname} if uri.hostname else set()), f"{variant}: callback host mismatch")
    require(paths == ({uri.path} if uri.hostname and uri.path else set()), f"{variant}: callback exact path mismatch")
    require(not any(item.get(ANDROID + attribute) for item in data for attribute in ("pathPrefix", "pathPattern", "pathAdvancedPattern")), f"{variant}: callback unexpectedly accepts a broader path")
    filters = receiver.findall("intent-filter")
    require(len(filters) == 1, f"{variant}: unexpected additional callback filter")
    actions = {item.get(ANDROID + "name") for item in filters[0].findall("action")}
    categories = {item.get(ANDROID + "name") for item in filters[0].findall("category")}
    require("android.intent.action.VIEW" in actions, f"{variant}: callback VIEW missing")
    require({"android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"} <= categories, f"{variant}: callback browser categories missing")


def inspect_variant(project, variant, values, analyzer):
    configured = all(values.get(key, "").strip() for key in (CLIENT, RELEASE, DEBUG))
    fields = build_config(project, variant)
    require(fields["ONEDRIVE_CONFIGURED"] == configured, f"{variant}: runtime configuration status mismatch")
    require(fields["ONEDRIVE_CLIENT_ID"] == (values[CLIENT] if configured else ""), f"{variant}: runtime client ID mismatch")
    require(fields["ONEDRIVE_REDIRECT_URI"] == (values[DEBUG if variant == "debug" else RELEASE] if configured else ""), f"{variant}: full runtime callback mismatch")
    manifests = list((project / "app/build/intermediates/merged_manifests").rglob("AndroidManifest.xml"))
    manifests = [path for path in manifests if variant in path.parts]
    require(len(manifests) == 1, f"{variant}: expected one merged Manifest")
    check_manifest(manifests[0].read_text(encoding="utf-8"), variant, values, configured)
    apks = list((project / f"app/build/outputs/apk/{variant}").glob("*.apk"))
    require(len(apks) == 1, f"{variant}: expected one APK")
    code, xml = run([str(analyzer), "manifest", "print", str(apks[0])], project)
    require(code == 0, f"{variant}: apkanalyzer manifest inspection failed")
    check_manifest(xml, variant, values, configured)


def main():
    local_source = ROOT / "local.properties"
    original_local = local_source.read_bytes() if local_source.exists() else None
    sdk = sdk_directory()
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    require(analyzer.is_file(), "SDK cmdline-tools/latest/bin/apkanalyzer is required")
    # Refuse an unignored or redirected output location before generating anything.
    code, _ = run(["git", "check-ignore", "--quiet", ".oauth-verification/probe"], ROOT)
    require(code == 0, ".oauth-verification/ must be ignored by Git")
    require(not WORK.is_symlink(), ".oauth-verification/ must not be a symbolic link")
    WORK.mkdir(exist_ok=True)
    results = []
    for name, values, error_property in fixtures():
        project = WORK / name
        require(not project.is_symlink(), f"{name}: fixture directory must not be a symbolic link")
        if project.exists():
            shutil.rmtree(project)
        copy_project(project)
        properties = {"sdk.dir": str(sdk), **values}
        (project / "local.properties").write_text("".join(f"{key}={properties_encode(value)}\n" for key, value in properties.items()), encoding="utf-8")
        print(f"{name}: building debug and release", flush=True)
        command = ["./gradlew", "--console=plain", "--build-cache", ":app:assembleDebug", ":app:assembleRelease", ":app:processDebugManifest", ":app:processReleaseManifest"]
        code, output = run(command, project)
        (WORK / f"{name}.log").write_text(sanitized(output, values, sdk), encoding="utf-8")
        try:
            if error_property:
                require(code != 0, "invalid complete configuration unexpectedly built")
                require(error_property in output, "configuration failure did not identify the relevant property")
                if "overlap" in name:
                    require(DEBUG in output and "overlap" in output.lower(), "overlap failure did not identify both callbacks and overlap")
            else:
                require(code == 0, "Gradle build failed; see sanitized fixture log")
                for variant in ("debug", "release"):
                    inspect_variant(project, variant, values, analyzer)
            results.append({"fixture": name, "passed": True, "expected": "configuration rejection" if error_property else "both variants built and inspected"})
            print(f"{name}: PASS", flush=True)
        except (AssertionError, ValueError, ET.ParseError) as error:
            results.append({"fixture": name, "passed": False, "error": sanitized(str(error), values, sdk)})
            print(f"{name}: FAIL ({results[-1]['error']})", flush=True)
        (WORK / "summary.json").write_text(json.dumps(results, ensure_ascii=False, indent=4) + "\n", encoding="utf-8")
    require((local_source.read_bytes() if local_source.exists() else None) == original_local, "user local.properties changed during verification")
    return 0 if all(result["passed"] for result in results) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, RuntimeError, OSError) as error:
        print(f"OAuth matrix stopped: {error}", file=sys.stderr)
        sys.exit(1)
