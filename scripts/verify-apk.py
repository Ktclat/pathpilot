#!/usr/bin/env python3
"""Verify package identity, source version and ARM64 ABI before publishing."""
import os
import pathlib
import re
import subprocess
import sys
import zipfile

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

root = pathlib.Path(__file__).resolve().parent.parent
properties = dict(line.strip().split('=', 1)
                  for line in (root / 'version.properties').read_text().splitlines()
                  if line.strip() and not line.lstrip().startswith('#'))
commit = subprocess.check_output(
    ['git', 'rev-parse', '--verify', 'HEAD'], cwd=root, text=True).strip()
expected_name = f"{properties['version']}+{commit[:7]}"
version_name = re.search(r"\bversionName='([^']+)'", badging)
version_code = re.search(r"\bversionCode='([0-9]+)'", badging)
if not version_name or version_name.group(1) != expected_name:
    raise SystemExit(f'APK version must match current source: {expected_name}')
if not version_code or int(version_code.group(1)) != int(properties['versionCode']):
    raise SystemExit(f"APK versionCode must match version.properties: {properties['versionCode']}")
print(f'Verified: dev.pathpilot.app, {expected_name}, ARM64 only, ZIP intact')
