# wax: Ansible deploy на VPS

**Домен портала:** [wax.su](https://wax.su)

**WAX — Workflows. Automation. eXecution.**

*Процессы. Автоматизация. Исполнение.*

Версия 0.1 • Только пилот с синтетическими данными до закрытия REQUIREMENTS.md.

Система называется **wax**. Служебные имена `nixu_*`, `/opt/nixu`, БД, volumes и realm сохранены для совместимости; см. [BRANDING.md](BRANDING.md).

## 1. Подготовить отдельный VPS

Ubuntu 24.04 или 26.04 LTS, amd64; ориентир для пилота — 4 vCPU / 8 ГБ RAM / 80–160 ГБ SSD, предпочтительно 8 vCPU / 16 ГБ при сборке контейнеров на этом же сервере. Это оценка ресурсов, не результат нагрузочного теста. Один сервер не даёт HA.

Нужны:
- DNS A домена `wax.su` на IP VPS. Не создавайте AAAA, если IPv6 не настроен.
- SSH-пользователь с sudo; вход по ключу. Проверенный host key в known_hosts.
- Свободные TCP 80/443. Порт 80 используется certbot standalone для ACME, сам портал работает по HTTPS.
- Доступ сервера к официальным пакетным репозиториям, Maven/npm/container registries.
- Домен портала `wax.su`. WUXU и его сервер не затрагиваются.

Ansible-контроллер — Ubuntu 24.04/26.04 на Linux или WSL2 с Python 3.12–3.14. Из обычного Windows PowerShell Ansible не запускают. Используем ansible-core 2.21: ветка 2.19 не поддерживает Python 3.14, установленный в Ubuntu 26.04.

## 2. Установить Ansible на контроллере

~~~bash
python3 -m venv .venv
. .venv/bin/activate
pip install 'ansible-core>=2.21,<2.22'
ansible-galaxy collection install -r infra/ansible/requirements.yml
python3 scripts/package_source.py
cd infra/ansible
cp inventory.example.ini inventory.ini
~~~

Для удалённого контроллера заполнить inventory.ini: адрес VPS, пользователя и SSH-порт.

При запуске прямо на VPS через PuTTY содержимое inventory.ini:

~~~ini
[nixu]
localhost ansible_connection=local ansible_python_interpreter=/usr/bin/python3
~~~

Запускайте Ansible из текущей SSH-сессии обычного пользователя с sudo, без `sudo ansible-playbook` и без `sudo su -`. Проверьте `ansible nixu -m command -a "printenv SSH_CONNECTION"`: адрес нужен защите UFW.
В group_vars/nixu.yml уже задано `nixu_domain: wax.su`; заполнить email ACME, SSH-порт и реальные администраторские CIDR. До запуска убедитесь, что текущий исходящий SSH-адрес входит в этот список. UFW включается после правил SSH; чужие существующие правила не сбрасываются.

Создать секреты:

~~~bash
python3 ../../scripts/make_secrets.py
ansible-vault encrypt secrets.yml
~~~

Файл содержит разные случайные пароли длиной 64 hex-символа. Не передавайте secrets.yml и пароль Vault третьим лицам. secrets.yml исключён из Git и исходного архива.

## 3. Проверить и развернуть

~~~bash
ansible nixu -m ping
ansible-playbook site.yml --syntax-check -e @secrets.yml --ask-vault-pass
ansible-playbook site.yml -e @secrets.yml --ask-vault-pass --ask-become-pass
~~~

Если sudo без пароля, последнюю опцию опустить. Плейбук:
1. Проверяет настройки, устанавливает официальный Docker/Compose, UFW, certbot.
2. Сохраняет исходники в каталог по SHA-256 исходного архива.
3. Получает сертификат; собирает backend с тестами и frontend.
4. Создаёт PostgreSQL, отдельные БД/роли, выполняет Flyway.
5. Запрещает runtime-роли менять/удалять журнал аудита.
6. Запускает Keycloak, Nginx и API; проверяет HTTPS /health.
7. Переключает /opt/nixu/current на проверенный release и устанавливает backup cron.

В Интернет опубликован только 443. PostgreSQL и API не публикуют host-порты. Учитывайте, что Docker-published ports обходят часть правил UFW; добавлять другие ports без проверки firewall нельзя.

На VPS образы собираются один раз для хеша исходников. Зависимости и Docker-базы пока закреплены тегами, а не digest; импорт в Nexus и воспроизводимая promotion между средами ещё не реализованы. Перед production следует закрепить все base images по проверенным digest и настроить vulnerability scanning.

## 4. Первый вход

Портал: https://wax.su  
Пользователь ERP: admin  
Временный пароль: nixu_admin_initial_password из Vault.

Keycloak потребует смену временного пароля и настройку OTP. Сохраните процедуру восстановления доступа, прежде чем приглашать пользователей.

Keycloak administration: https://wax.su/auth/admin/ — доступ ограничен nixu_admin_cidrs.
Bootstrap login: bootstrap-admin, пароль nixu_keycloak_bootstrap_password. После создания постоянного администратора Keycloak отключите bootstrap-учётную запись и настройте её MFA/восстановление согласно политике компании.

Обычные пользователи создаются в realm nixu, затем им назначаются роли в ERP. В демо seed есть grants purchasing, sales, warehouse, manager, hr, employee, но в Keycloak эти пользователи автоматически не создаются. Если они не нужны, заблокируйте grants.

Required actions действуют для новых пользователей; повторный realm import не меняет существующий realm. Изменения работающего Keycloak делаются через его административный API/панель и отдельные миграции.

## 5. Проверка после установки

~~~bash
sudo bash -c '
set -e
cd /opt/nixu/current
docker compose --env-file secrets.env ps
docker compose --env-file secrets.env logs --tail=100 api
'
curl --fail https://wax.su/health
~~~

Проверить вход с MFA, отказ без доступа к юрлицу, закупку/продажу, PDF, выгрузку и перезапуск контейнеров с сохранением данных. Затем проверить внешним сканированием, что 5432 и 8080 недоступны.

Не публикуйте вывод docker compose config: он разворачивает секреты. Для проверки используйте config --quiet.

## 6. Обновление

Сохранить прежние Vault secrets. Повторно собрать исходный архив, выполнить playbook. До миграции он делает snapshot существующего контура. Старый API останавливается непосредственно перед миграцией; для пилота предусмотрено окно обслуживания.

Изменение пароля в Vault само по себе не меняет пароль существующей роли PostgreSQL или пользователя Keycloak. Ротация требует отдельной согласованной операции; нельзя просто заменить secrets.yml и ожидать автоматического обновления БД.

Плейбук не выполняет down -v, не очищает volumes и не удаляет прошлые release-каталоги.

## 7. Backup

Ежедневно в 02:15 по часовому поясу VPS выполняется /opt/nixu/backup.sh. Он кратко останавливает API и Keycloak, создаёт pg_dump двух БД и архив файлов, затем запускает сервисы. Копии находятся в /var/backups/nixu. Вложения и БД относятся к одному окну без писателей.

Это ночной snapshot; **RPO 15 минут не обеспечивается**. Нужны pgBackRest/WAL/PITR и согласованная стратегия S3/файлов.

Опционально задайте nixu_restic_repository и отдельный nixu_restic_password. Инициализируйте внешний repository командой restic init в защищённой среде. Предоставьте credentials транспорта согласно выбранному backend restic; S3 credentials этот плейбук не создаёт. После включения проверьте restic snapshots и тестовое восстановление.

Локальные dump не зашифрованы отдельно и защищены правами root. Для реальных данных нужна off-host зашифрованная копия. Cron сам по себе не оповещает о сбое: добавьте мониторинг до эксплуатации.

## 8. Восстановление и откат

Сначала восстановить в изолированный VPS, не поверх рабочего контура:
1. Развернуть ту же версию образов и схемы; остановить api/keycloak.
2. Проверить SHA256SUMS архива.
3. Восстановить nixu.dump и keycloak.dump в пустые БД с исходными владельцами и правами.
4. Восстановить files.tar.gz в volume nixu_files, сохранить uid/gid 10001.
5. Запустить сервисы, проверить MFA, документы, суммы, вложения, права и аудит.
6. Только после приёмки переключать DNS/трафик.

Код можно вернуть на прошлые образы, только если новая схема совместима назад. Нельзя выполнять автоматический downgrade БД или восстанавливать старый dump поверх новых операций. Для несовместимой миграции применяется forward fix либо согласованное восстановление всей согласованной копии с учётом потери операций.

Тест восстановления на чистой VM и фактический RTO ещё не выполнены.

## 9. Источники

- [Матрица Ansible / Python](https://docs.ansible.com/projects/ansible-core/devel/reference_appendices/release_and_maintenance.html#ansible-core-support-matrix)
- [Установка Docker на Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
- [Keycloak: hostname](https://www.keycloak.org/server/hostname)
- [Keycloak: reverse proxy](https://www.keycloak.org/server/reverseproxy)
- [Keycloak: импорт realm](https://www.keycloak.org/server/importExport)
- [Ansible Vault](https://docs.ansible.com/ansible/latest/vault_guide/index.html)
- [PostgreSQL: backup](https://www.postgresql.org/docs/18/backup.html)
