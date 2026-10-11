#!/usr/bin/env python3
"""Generate local.properties for CI from environment variables.

GitHub secret names cannot contain dots, so each property key maps to an upper-case
variable: onedrive.clientId -> ONEDRIVE_CLIENT_ID, release.storeFile -> RELEASE_STORE_FILE.
The keystore itself arrives base64-encoded in RELEASE_STORE_FILE_BASE64 and is decoded into
RUNNER_TEMP; release.storeFile then points at that absolute path. Unset or empty variables
are omitted, so Gradle treats the corresponding feature as unconfigured.
"""
import base64
import os
import sys
from pathlib import Path

KEYS = {
    "onedrive.clientId": "ONEDRIVE_CLIENT_ID",
    "onedrive.redirectUri": "ONEDRIVE_REDIRECT_URI",
    "onedrive.debugRedirectUri": "ONEDRIVE_DEBUG_REDIRECT_URI",
    "release.storePassword": "RELEASE_STORE_PASSWORD",
    "release.keyAlias": "RELEASE_KEY_ALIAS",
    "release.keyPassword": "RELEASE_KEY_PASSWORD",
}


def encode(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def main() -> None:
    properties = {}
    for key, variable in KEYS.items():
        value = os.environ.get(variable, "").strip()
        if value:
            properties[key] = value
    keystore = os.environ.get("RELEASE_STORE_FILE_BASE64", "").strip()
    if keystore:
        target = Path(os.environ["RUNNER_TEMP"]) / "release.jks"
        target.write_bytes(base64.b64decode(keystore))
        properties["release.storeFile"] = str(target)
    elif sys.argv[1:] == ["--require-signing"]:
        sys.exit("RELEASE_STORE_FILE_BASE64 secret is not set")
    if sys.argv[1:] == ["--require-signing"]:
        missing = [k for k in ("release.storeFile", "release.storePassword", "release.keyAlias", "release.keyPassword") if k not in properties]
        if missing:
            sys.exit("Missing release signing secrets for: " + ", ".join(missing))
    Path("local.properties").write_text("".join(f"{k}={encode(v)}\n" for k, v in properties.items()), encoding="utf-8")


main()
