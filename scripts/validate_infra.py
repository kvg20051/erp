#!/usr/bin/env python3
from pathlib import Path
import yaml,json,re,shlex
from jinja2 import Environment,StrictUndefined
root=Path(__file__).resolve().parents[1]
count=0
for folder in ['infra','.github']:
    for p in (root/folder).rglob('*.yml'):
        if p.name in {'secrets.yml','inventory.yml'}:continue
        list(yaml.safe_load_all(p.read_text(encoding='utf-8')));count+=1
values=yaml.safe_load((root/'infra/ansible/group_vars/nixu.yml').read_text())
values.update(nixu_domain='erp.example.org',nixu_acme_email='admin@example.org',nixu_release='test-release',
              nixu_admin_cidrs=['192.0.2.1/32'],nixu_restic_repository='')
for key in ['postgres','owner','app','keycloak_db','keycloak_bootstrap','admin_initial','restic']:
    values['nixu_'+key+'_password']='0'*64
env=Environment(undefined=StrictUndefined,keep_trailing_newline=True)
env.filters['quote']=shlex.quote
env.filters['to_json']=json.dumps
out=root/'dist/infra-check';out.mkdir(parents=True,exist_ok=True)
for p in (root/'infra/ansible/templates').glob('*.j2'):
    rendered=env.from_string(p.read_text()).render(**values)
    (out/p.name.removesuffix('.j2')).write_text(rendered,encoding='utf-8')
realm=json.loads((out/'realm.json').read_text())
assert realm['accessTokenLifespan']<=240
assert any(x['alias']=='CONFIGURE_TOTP' and x['defaultAction'] for x in realm['requiredActions'])
compose=yaml.safe_load((root/'infra/compose/compose.yml').read_text())
assert 'ports' not in compose['services']['postgres']
assert 'ports' not in compose['services']['api']
assert compose['services']['web']['ports']==['443:443']
assert 'demo' not in json.dumps(compose)
assert compose['services']['api']['environment']['FLYWAY_ENABLED']=='false'
assert 'env_file' not in compose['services']['api']
print(f'PASS: {count} YAML files, all Jinja templates, realm MFA/TTL, network exposure and secret isolation.')

playbook=yaml.safe_load((root/"infra/ansible/site.yml").read_text())
private_templates=next(t for t in playbook[0]["tasks"] if t["name"]=="Write private runtime files")["loop"]
assert next(x for x in private_templates if x["dest"]=="initdb.sh")["mode"] == "0644", "PostgreSQL non-root entrypoint must read initdb.sh"
nginx=(out/"nginx.conf").read_text()
assert "~^/auth/ \"\";" in nginx, "Keycloak must own its login CSP/frame policy"
print("PASS: PostgreSQL init permissions and Keycloak header scope.")
