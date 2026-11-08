#!/usr/bin/env python3
"""update-windows.json для обновления RVC для Windows из приложения (desktop/.../data/Updates.kt).

Использование:
    python scripts/make-windows-update.py <версия> <каталог dist> <owner/repo> <файл заметок>

Ищет dist/RVC-windows-<версия>.msi, считает SHA-256 и размер, пишет dist/update-windows.json.
Заметки — по строке на пункт (пустые строки и строки с # пропускаются). Файл кладётся в релиз
windows-v<версия> вместе с MSI и EXE.
"""
import hashlib
import json
import os
import sys


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    version, dist, repo, notes_file = sys.argv[1:]
    msi_name = f'RVC-windows-{version}.msi'
    msi = os.path.join(dist, msi_name)
    if not os.path.exists(msi):
        sys.exit(f'нет {msi}')
    with open(notes_file, encoding='utf-8') as f:
        notes = [line.strip().lstrip('-•').strip() for line in f if line.strip() and not line.startswith('#')]
    tag = f'windows-v{version}'
    data = {
        'version': version,
        'notes': notes,
        'page': f'https://github.com/{repo}/releases/tag/{tag}',
        'msi': {
            'url': f'https://github.com/{repo}/releases/download/{tag}/{msi_name}',
            'sha256': sha256(msi),
            'size': os.path.getsize(msi),
        },
    }
    out = os.path.join(dist, 'update-windows.json')
    with open(out, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write('\n')
    print(f'{out}: {version}, {data["msi"]["size"]} байт, sha256 {data["msi"]["sha256"]}')


if __name__ == '__main__':
    main()
