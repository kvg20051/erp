-- Rename only unchanged synthetic seed labels; preserve IDs and user-edited names.
UPDATE organizations SET name = 'wax · Демонстрационная компания'
WHERE id = 'demo' AND name = 'nixu · Демонстрационная компания';
UPDATE organizations SET name = 'wax · Второе юридическое лицо'
WHERE id = 'second' AND name = 'nixu · Второе юридическое лицо';
UPDATE master SET name = 'Сервер wax R1'
WHERE id = 'server' AND org = 'demo' AND kind = 'item' AND name = 'Сервер nixu R1';
