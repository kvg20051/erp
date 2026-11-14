# Быстрый просмотр готовой сборки

В полном ZIP приложены release/nixu-0.1.0.jar и release/web. Нужны только Java 25 и Node.js 24. Maven, npm и Docker для этого режима не нужны.

Первый терминал из корня распакованного проекта, PowerShell:

~~~powershell
$env:DEMO_PASSWORD = Read-Host "Задайте пароль локального демо, не менее 12 символов"
java -jar release/nixu-0.1.0.jar --spring.profiles.active=demo --server.address=127.0.0.1
~~~

Linux:

~~~bash
read -rs -p "Задайте пароль локального демо: " DEMO_PASSWORD
export DEMO_PASSWORD
java -jar release/nixu-0.1.0.jar --spring.profiles.active=demo --server.address=127.0.0.1
~~~

Второй терминал из корня проекта:

~~~bash
node scripts/demo-server.mjs
~~~

Открыть http://127.0.0.1:5173. Выбрать admin или другую демонстрационную роль и введённый вами пароль. Подробный сценарий в PROTOTYPE.md.

Остановка: Ctrl+C в каждом терминале. В режиме demo данные H2 находятся в ./data. Не запускайте два backend с одной файловой БД одновременно. Если Windows запрещает запись в каталог, распакуйте проект в собственный каталог с правом записи либо используйте PostgreSQL. Файловая H2 предназначена только для демонстрации.

Для подключения своего тестового PostgreSQL переопределите URL и пользователя аргументами Spring; пароль задайте SPRING_DATASOURCE_PASSWORD в окружении, не в командной строке. Для production используйте исключительно Ansible/Keycloak из docs/DEPLOY.md.

Браузер и API в этом режиме слушают только 127.0.0.1. Общий пароль demo не переносится на VPS.
