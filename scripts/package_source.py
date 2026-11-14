#!/usr/bin/env python3
"""Deterministic source bundle; excludes employee CSV, secrets and local data."""
from pathlib import Path
import tarfile,gzip,hashlib
root=Path(__file__).resolve().parents[1]
out=root/'dist';out.mkdir(exist_ok=True)
allowed=['backend','apps/web','infra','scripts','docs','contracts','tests/fixtures','.github','assets/branding']
files=[]
for folder in allowed:
    for p in (root/folder).rglob('*'):
        if not p.is_file(): continue
        rel=p.relative_to(root)
        if any(x in {'node_modules','target','dist','data','playwright-report','test-results','__pycache__'} for x in rel.parts): continue
        if p.suffix in {'.log','.tsbuildinfo'} or p.name in {'secrets.yml','secrets.env','inventory.ini','.env','nixu-logo.jpg','nixu_logo.png'}: continue
        files.append(p)
for name in ['README.md','START-DEMO.md','PROTOTYPE.md','NFR.md','fr.md','STACK.md','INFRA.md','ARCHITECTURE.md','.gitignore','.dockerignore']:
    if (root/name).is_file():files.append(root/name)
with (out/'wax-source.tar.gz').open('wb') as raw:
    with gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as gz:
        with tarfile.open(fileobj=gz,mode='w') as tar:
            for p in sorted(set(files)):
                info=tar.gettarinfo(str(p),arcname=p.relative_to(root).as_posix())
                info.uid=info.gid=0;info.uname=info.gname='';info.mtime=0
                info.mode=0o755 if p.suffix=='.sh' else 0o644
                with p.open('rb') as f:tar.addfile(info,f)
p=out/'wax-source.tar.gz'
digest=hashlib.sha256(p.read_bytes()).hexdigest()
(out/'wax-source.tar.gz.sha256').write_text(digest+'  wax-source.tar.gz\n')
print(p)
