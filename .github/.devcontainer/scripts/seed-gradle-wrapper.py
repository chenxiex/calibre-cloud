#!/usr/bin/env python3
"""Seed a verified ZIP into the default Gradle 9.3.1 Wrapper cache layout."""

import hashlib
import os
from pathlib import Path
import shutil
import sys
import tempfile


def seed_wrapper(distribution_url: str, archive: Path, gradle_user_home: Path) -> None:
    # Gradle PathAssembler uses the unsigned MD5 of the URL encoded in base 36.
    # This public, canonical URL has no credentials or characters needing URI escaping.
    value = int.from_bytes(hashlib.md5(distribution_url.encode("utf-8"), usedforsecurity=False).digest(), "big")
    digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    url_hash = ""
    while value:
        value, digit = divmod(value, 36)
        url_hash = digits[digit] + url_hash
    url_hash = url_hash or "0"

    cache_directory = gradle_user_home / "wrapper" / "dists" / archive.stem / url_hash
    cached_archive = cache_directory / archive.name
    # Let the real Wrapper validate and extract the ZIP, then create its own .ok marker.
    if cached_archive.exists() or cached_archive.with_suffix(".zip.ok").exists():
        return

    cache_directory.mkdir(parents=True, exist_ok=True)
    temporary_archive = None
    try:
        with tempfile.NamedTemporaryFile(dir=cache_directory, prefix=".seed-", delete=False) as temporary:
            temporary_archive = Path(temporary.name)
        shutil.copyfile(archive, temporary_archive)
        os.replace(temporary_archive, cached_archive)
    finally:
        if temporary_archive is not None:
            temporary_archive.unlink(missing_ok=True)


if __name__ == "__main__":
    seed_wrapper(sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]))
