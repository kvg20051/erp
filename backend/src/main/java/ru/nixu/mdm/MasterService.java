package ru.nixu.mdm;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.nixu.core.*;

@Service
@Transactional
public class MasterService extends Store {
  public MasterService(JdbcTemplate d, Access a) {
    super(d, a);
  }

  private static final Set<String> KINDS =
      Set.of(
          "partner",
          "contract",
          "item",
          "department",
          "position",
          "currency",
          "rate",
          "unit",
          "vat",
          "warehouse",
          "settings",
          "group",
          "calendar",
          "pricelist");

  public List<Map<String, Object>> list(String org, String kind) {
    enter(org);
    return db
        .queryForList(
            "select * from master where org=? and kind=? order by name limit 500", org, kind)
        .stream()
        .map(r -> unpack(r, "data"))
        .toList();
  }

  public Map<String, Object> save(String org, Map<String, Object> p) {
    enter(org);
    access.role("mdm");
    String kind = Json.required(p, "kind");
    ApiError.require(KINDS.contains(kind), "Неизвестный справочник");
    String name = Json.required(p, "name");
    var data = Json.read(p.getOrDefault("data", Map.of()));
    if (kind.equals("partner")) validateTax(data);
    if (kind.equals("rate")) {
      ApiError.require(
          Json.str(data, "currency").matches("[A-Z]{3}"), "Код валюты: три латинские буквы");
      LocalDate.parse(Json.required(data, "date"));
      ApiError.require(Json.dec(data.get("value")).signum() > 0, "Курс должен быть положительным");
    }
    if (kind.equals("item")) {
      ApiError.require(
          Set.of("GOODS", "SERVICE", "LICENSE").contains(Json.str(data, "type")),
          "Укажите вид номенклатуры");
      ApiError.require(
          Json.dec(data.get("vat")).signum() >= 0
              && Json.dec(data.get("vat")).compareTo(new java.math.BigDecimal("100")) <= 0,
          "НДС от 0 до 100");
    }
    String id = Json.str(p, "id");
    boolean active = !Boolean.FALSE.equals(p.get("active"));
    if (id.isBlank()) {
      id = Json.id();
      db.update(
          "insert into master(id,org,kind,name,data,active) values(?,?,?,?,?,?)",
          id,
          org,
          kind,
          name,
          Json.write(data),
          active);
    } else {
      var old = one("select * from master where org=? and id=?", org, id);
      ApiError.require(kind.equals(old.get("kind")), "Тип записи менять нельзя");
      int n =
          db.update(
              "update master set name=?,data=?,active=?,version=version+1 where org=? and id=? and"
                  + " version=?",
              name,
              Json.write(data),
              active,
              org,
              id,
              Json.dec(p.get("version")).intValueExact());
      if (n != 1) throw new ApiError(409, "Запись изменена другим пользователем");
    }
    audit(org, "MASTER_SAVED", id);
    return unpack(one("select * from master where id=? and org=?", id, org), "data");
  }

  private void validateTax(Map<String, Object> p) {
    String inn = Json.str(p, "inn"), kpp = Json.str(p, "kpp");
    if (!inn.isBlank()) {
      ApiError.require(inn.matches("[0-9]{10}|[0-9]{12}"), "ИНН должен содержать 10 или 12 цифр");
      int[] w =
          inn.length() == 10
              ? new int[] {2, 4, 10, 3, 5, 9, 4, 6, 8}
              : new int[] {7, 2, 4, 10, 3, 5, 9, 4, 6, 8};
      ApiError.require(
          check(inn, w) == inn.charAt(w.length) - 48, "Неверное контрольное число ИНН");
      if (inn.length() == 12)
        ApiError.require(
            check(inn, new int[] {3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8}) == inn.charAt(11) - 48,
            "Неверное контрольное число ИНН");
    }
    if (!kpp.isBlank())
      ApiError.require(kpp.matches("[0-9]{4}[0-9A-Z]{2}[0-9]{3}"), "Неверный формат КПП");
  }

  private int check(String s, int[] w) {
    int sum = 0;
    for (int i = 0; i < w.length; i++) sum += (s.charAt(i) - 48) * w[i];
    return sum % 11 % 10;
  }

  public void closePeriod(String org, String until) {
    enter(org);
    access.role("admin");
    LocalDate date = LocalDate.parse(until);
    db.update("delete from periods where org=?", org);
    db.update("insert into periods values(?,?)", org, date);
    audit(org, "PERIOD_CLOSED", until);
  }

  public void openPeriod(String org) {
    enter(org);
    access.role("admin");
    db.update("delete from periods where org=?", org);
    audit(org, "PERIOD_OPENED", org);
  }

  public List<Map<String, Object>> auditLog(String org) {
    enter(org);
    access.role("admin");
    return db.queryForList(
        "select * from audit where org=? or org is null order by created_at desc limit 300", org);
  }

  public List<Map<String, Object>> outbox(String org) {
    enter(org);
    access.role("hr", "manager", "purchasing", "sales", "warehouse");
    return db
        .queryForList("select * from outbox where org=? order by created_at desc limit 200", org)
        .stream()
        .map(r -> unpack(r, "payload"))
        .toList();
  }

  public List<Map<String, Object>> grants() {
    access.role("admin");
    return db.queryForList("select * from access_grant order by username");
  }

  public void grant(Map<String, Object> p) {
    access.role("admin");
    String u = Json.required(p, "username"),
        roles = Json.required(p, "roles"),
        orgs = Json.required(p, "orgs");
    for (String org : orgs.split(",")) access.org(org);
    ApiError.require(
        Arrays.stream(roles.split(","))
            .allMatch(
                Set.of(
                        "admin",
                        "mdm",
                        "manager",
                        "purchasing",
                        "sales",
                        "warehouse",
                        "hr",
                        "employee",
                        "pii",
                        "archive",
                        "profit")
                    ::contains),
        "Неизвестная роль");
    if (db.update(
            "update access_grant set roles=?,orgs=?,enabled=? where username=?",
            roles,
            orgs,
            !Boolean.FALSE.equals(p.get("enabled")),
            u)
        == 0)
      db.update(
          "insert into access_grant values(?,?,?,?)",
          u,
          roles,
          orgs,
          !Boolean.FALSE.equals(p.get("enabled")));
    audit(null, "ACCESS_CHANGED", u);
  }
}
