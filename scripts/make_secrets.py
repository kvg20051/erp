#!/usr/bin/env python3
from pathlib import Path
import secrets,os
target=Path(__file__).resolve().parents[1]/'infra/ansible/secrets.yml'
names=['postgres','owner','app','keycloak_db','keycloak_bootstrap','admin_initial','restic']
fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w',encoding='utf-8') as out:
    for name in names:out.write(f'nixu_{name}_password: {secrets.token_hex(32)}\n')
print(f'Created {target}. Encrypt with ansible-vault before storing or sharing.')
