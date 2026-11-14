package ru.nixu.core;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

public abstract class Store {
  protected final JdbcTemplate db;
  protected final Access access;

  protected Store(JdbcTemplate d, Access a) {
    db = d;
    access = a;
  }

  protected void enter(String org) {
    access.org(org);
    db.queryForList("select id from organizations where id=? for update", org);
  }

  protected Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new ApiError(404, "Объект не найден");
    return rows.getFirst();
  }

  protected void audit(String org, String action, String id) {
    db.update(
        "insert into audit(id,org,actor,action,object_id,detail,request_id) values(?,?,?,?,?,?,?)",
        Json.id(),
        org,
        access.user(),
        action,
        id,
        "{}",
        Json.id());
  }

  protected void event(String org, String topic, Object data) {
    db.update(
        "insert into outbox(id,org,topic,payload) values(?,?,?,?)",
        Json.id(),
        org,
        topic,
        Json.write(data));
  }

  protected void period(String org, LocalDate date) {
    ApiError.require(
        date.getYear() >= 2000 && date.getYear() <= 2100, "Дата вне допустимого диапазона");
    var rows = db.queryForList("select until_date from periods where org=?", org);
    if (!rows.isEmpty()
        && !date.isAfter(LocalDate.parse(rows.getFirst().get("until_date").toString())))
      throw ApiError.bad("Учётный период закрыт");
  }

  protected Map<String, Object> master(String org, String id, String kind) {
    return one(
        "select * from master where org=? and id=? and kind=? and active=true", org, id, kind);
  }

  protected Map<String, Object> unpack(Map<String, Object> r, String key) {
    var m = new LinkedHashMap<>(r);
    if (m.containsKey(key)) m.put(key, Json.read(m.get(key)));
    return m;
  }

  protected BigDecimal rate(String org, String currency, LocalDate date) {
    if (currency.equals("RUB")) return BigDecimal.ONE;
    return db
        .queryForList("select data from master where org=? and kind='rate' and active=true", org)
        .stream()
        .map(r -> Json.read(r.get("data")))
        .filter(
            p ->
                currency.equals(p.get("currency"))
                    && !LocalDate.parse(Json.str(p, "date")).isAfter(date))
        .max(Comparator.comparing(p -> Json.str(p, "date")))
        .map(
            p -> {
              var v = Json.dec(p.get("value"));
              ApiError.require(v.signum() > 0, "Курс должен быть положительным");
              return v;
            })
        .orElseThrow(() -> ApiError.bad("Нет курса " + currency + " на дату документа"));
  }
}
