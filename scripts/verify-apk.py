#!/usr/bin/env python3
"""Reject non-ARM64 APKs and screenshot-mode builds before publishing CI artifacts."""
import pathlib
import re
import subprocess
import sys
import zipfile
import os

apk = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    if archive.testzip() is not None:
        raise SystemExit('APK ZIP integrity check failed')
    abis = {name.split('/')[1] for name in archive.namelist()
            if name.startswith('lib/') and name.endswith('.so')}
    if abis != {'arm64-v8a'}:
        raise SystemExit(f'Expected arm64-v8a only, found {sorted(abis)}')

aapt = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/aapt'
badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
if not re.search(r"^package: name='dev\.pathpilot\.app' ", badging, re.MULTILINE):
    raise SystemExit('Unexpected package identity: refusing screenshot or unrelated APK')
print('Verified: dev.pathpilot.app, ARM64 only, ZIP intact')
