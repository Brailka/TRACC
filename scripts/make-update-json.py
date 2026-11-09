#!/usr/bin/env python3
"""Собирает update.json для обновления из приложения.

Использование: make-update-json.py <версия> <каталог с APK> <repo owner/name> [заметки.txt]
APK должны называться TRACC-<версия>-<abi>.apk (abi: arm64-v8a, armeabi-v7a, x86_64, x86, universal).
"""
import hashlib
import json
import os
import sys

version, apk_dir, repo = sys.argv[1].lstrip('v'), sys.argv[2], sys.argv[3]
notes_file = sys.argv[4] if len(sys.argv) > 4 else None
major, minor, patch = (int(p) for p in version.split('.')[:3])
assets = {}
for name in sorted(os.listdir(apk_dir)):
    if not (name.startswith(f'TRACC-{version}-') and name.endswith('.apk')):
        continue
    abi = name[len(f'TRACC-{version}-'):-4]
    path = os.path.join(apk_dir, name)
    digest = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    assets[abi] = {
        'url': f'https://github.com/{repo}/releases/download/v{version}/{name}',
        'sha256': digest,
        'size': os.path.getsize(path),
    }
notes = []
if notes_file and os.path.exists(notes_file):
    notes = [l[2:].strip() for l in open(notes_file, encoding='utf8') if l.startswith('- ')]
info = {'versionCode': major * 10000 + minor * 100 + patch, 'versionName': version, 'notes': notes, 'assets': assets}
json.dump(info, open(os.path.join(apk_dir, 'update.json'), 'w', encoding='utf8'), ensure_ascii=False, indent=2)
with open(os.path.join(apk_dir, 'SHA256SUMS'), 'w', encoding='utf8') as f:
    for abi, a in assets.items():
        f.write(f"{a['sha256']}  TRACC-{version}-{abi}.apk\n")
print(json.dumps(info, ensure_ascii=False, indent=2))
