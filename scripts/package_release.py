#!/usr/bin/env python3
"""Build a portable ZIP containing reviewed sources and prebuilt demo artifacts."""
from pathlib import Path
import hashlib
import subprocess
import sys
import tarfile
import zipfile

root = Path(__file__).resolve().parents[1]
jar = root / 'release/nixu-0.1.0.jar'
web = root / 'release/web'
if not jar.is_file() or not (web / 'index.html').is_file():
    raise SystemExit('Build the Java JAR and web assets and copy them into release/ first.')
subprocess.run([sys.executable, str(root / 'scripts/package_source.py')], check=True)
target = root.parent / 'wax-prototype-0.1.zip'
prefix = 'wax-prototype/'
checksums = []

def add_bytes(archive, name, data, mode=0o644):
    entry = zipfile.ZipInfo(prefix + name, date_time=(2026,11,14,0,0,0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.external_attr = (0o100000 | mode) << 16
    archive.writestr(entry, data)
    checksums.append(hashlib.sha256(data).hexdigest() + '  ' + name)

with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    with tarfile.open(root / 'dist/wax-source.tar.gz', 'r:gz') as sources:
        for item in sources.getmembers():
            if item.isfile():
                add_bytes(archive, item.name, sources.extractfile(item).read(), item.mode)
    for path in sorted((root / 'release').rglob('*')):
        if path.is_file():
            add_bytes(archive, path.relative_to(root).as_posix(), path.read_bytes())
    for name in ['wax-source.tar.gz', 'wax-source.tar.gz.sha256']:
        add_bytes(archive, 'dist/' + name, (root / 'dist' / name).read_bytes())
    add_bytes(archive, 'SHA256SUMS', ('\n'.join(checksums)+'\n').encode())

with zipfile.ZipFile(target) as archive:
    assert archive.testzip() is None
    names = archive.namelist()
    required = [
        'START-DEMO.md', 'PROTOTYPE.md', 'docs/DEPLOY.md', 'docs/REQUIREMENTS.md',
        'docs/VERIFICATION.md', 'infra/ansible/site.yml', 'infra/compose/compose.yml',
        'release/nixu-0.1.0.jar', 'release/web/index.html', 'scripts/demo-server.mjs',
        'dist/wax-source.tar.gz', 'SHA256SUMS',
    ]
    assert all(prefix+name in names for name in required)
    assert prefix+'employees.csv' not in names
    assert not any('/node_modules/' in n or '/target/' in n or '/.git/' in n for n in names)
    assert not any(Path(n).name in {'secrets.yml', 'secrets.env', 'inventory.ini', '.env'} for n in names)
digest = hashlib.sha256(target.read_bytes()).hexdigest()
target.with_suffix('.zip.sha256').write_text(digest + '  ' + target.name + '\n', encoding='utf-8')
print(f'PASS: {len(names)} entries, CRC verified, no local secrets or real employee CSV.')
print(f'{target}\n{target.stat().st_size:,} bytes\nSHA256 {digest}')
