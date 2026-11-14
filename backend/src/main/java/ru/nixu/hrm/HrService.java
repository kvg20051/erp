package ru.nixu.hrm;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.nixu.core.*;

@Service
@Transactional
public class HrService extends Store {
  public HrService(JdbcTemplate d, Access a) {
    super(d, a);
  }

  private Map<String, Object> employee(String org, String id) {
    var r = one("select * from employee where org=? and id=?", org, id);
    if (!access.has("admin")
        && !access.has("hr")
        && !access.has("manager")
        && !access.user().equals(r.get("username")))
      throw new ApiError(403, "Нет доступа к карточке сотрудника");
    if ("FIRED".equals(r.get("state")) && !access.has("archive"))
      throw new ApiError(403, "Нет доступа к архиву персонала");
    return r;
  }

  private Map<String, Object> safe(Map<String, Object> r) {
    var m = unpack(r, "data");
    var d = Json.read(m.get("data"));
    if (!access.has("pii"))
      for (String k : List.of("passport", "phone", "address", "birthDate", "birthYear"))
        d.remove(k);
    else audit(Json.str(r, "org"), "PII_VIEWED", Json.str(r, "id"));
    return m;
  }

  public List<Map<String, Object>> list(String org) {
    enter(org);
    return db
        .queryForList("select * from employee where org=? order by name limit 1000", org)
        .stream()
        .filter(r -> !"FIRED".equals(r.get("state")) || access.has("archive"))
        .filter(
            r ->
                access.has("admin")
                    || access.has("hr")
                    || access.has("manager")
                    || access.user().equals(r.get("username")))
        .map(this::safe)
        .toList();
  }

  public Map<String, Object> get(String org, String id) {
    enter(org);
    var r = safe(employee(org, id));
    r.put(
        "equipment",
        db.queryForList(
            "select item,serial,warehouse from stock where org=? and employee=? and qty>0",
            org,
            id));
    r.put(
        "events",
        db.queryForList(
            "select kind,event_date from hr_event where org=? and employee_id=? order by"
                + " event_date",
            org,
            id));
    r.put(
        "absences",
        db.queryForList(
            "select * from absence where org=? and employee_id=? order by starts", org, id));
    return r;
  }

  public Map<String, Object> save(String org, Map<String, Object> p) {
    enter(org);
    access.role("hr");
    access.role("pii");
    String id = Json.str(p, "id"),
        name = Json.required(p, "name"),
        code = Json.required(p, "code"),
        username = Json.str(p, "username");
    var data = Json.read(p.getOrDefault("data", Map.of()));
    if (id.isBlank()) {
      id = Json.id();
      db.update(
          "insert into employee(id,org,code,name,username,data) values(?,?,?,?,?,?)",
          id,
          org,
          code,
          name,
          username,
          Json.write(data));
    } else {
      var old = employee(org, id);
      ApiError.require(!"FIRED".equals(old.get("state")), "Архивная карточка не редактируется");
      if (db.update(
              "update employee set code=?,name=?,username=?,data=?,version=version+1 where org=?"
                  + " and id=? and version=?",
              code,
              name,
              username,
              Json.write(data),
              org,
              id,
              Json.dec(p.get("version")).intValueExact())
          != 1) throw new ApiError(409, "Карточка изменена другим пользователем");
    }
    audit(org, "EMPLOYEE_SAVED", id);
    return get(org, id);
  }

  public Map<String, Object> event(String org, String id, Map<String, Object> p) {
    enter(org);
    access.role("hr");
    var r = employee(org, id);
    String kind = Json.required(p, "kind");
    LocalDate date = LocalDate.parse(Json.required(p, "date"));
    String reason = Json.required(p, "reason");
    ApiError.require(
        !date.isAfter(LocalDate.now()), "Отложенные кадровые события пока не поддерживаются");
    String state = Json.str(r, "state");
    if (kind.equals("HIRE")) {
      ApiError.require(state.equals("INCOMPLETE"), "Сотрудник уже принят");
      var d = Json.read(r.get("data"));
      for (String k :
          List.of(
              "firstName",
              "lastName",
              "birthDate",
              "phone",
              "address",
              "passport",
              "department",
              "position",
              "manager")) Json.required(d, k);
      ApiError.require(
          !Json.str(d, "middleName").isBlank() || Boolean.TRUE.equals(d.get("noMiddleName")),
          "Укажите отчество или признак отсутствия");
      ApiError.require(
          LocalDate.parse(Json.str(d, "birthDate")).isBefore(date),
          "Дата рождения должна быть раньше даты приёма");
      master(org, Json.str(d, "department"), "department");
      master(org, Json.str(d, "position"), "position");
      db.update(
          "update employee set state='ACTIVE',hired_on=?,version=version+1 where org=? and id=?",
          date,
          org,
          id);
    } else if (kind.equals("FIRE")) {
      ApiError.require(state.equals("ACTIVE"), "Сотрудник не работает");
      ApiError.require(
          !date.isBefore(LocalDate.parse(r.get("hired_on").toString())),
          "Увольнение раньше приёма");
      ApiError.require(
          db.queryForObject(
                  "select count(*) from stock where org=? and employee=? and qty>0",
                  Integer.class,
                  org,
                  id)
              == 0,
          "Не завершён обходной лист: верните оборудование");
      db.update(
          "update employee set state='FIRED',fired_on=?,version=version+1 where org=? and id=?",
          date,
          org,
          id);
      if (!Json.str(r, "username").isBlank())
        db.update("update access_grant set enabled=false where username=?", r.get("username"));
    } else if (kind.equals("TRANSFER")) {
      ApiError.require(state.equals("ACTIVE"), "Сотрудник не работает");
      var d = Json.read(r.get("data"));
      String dep = Json.required(p, "department");
      master(org, dep, "department");
      d.put("department", dep);
      db.update(
          "update employee set data=?,version=version+1 where org=? and id=?",
          Json.write(d),
          org,
          id);
    } else throw ApiError.bad("Неизвестное кадровое событие");
    db.update(
        "insert into hr_event values(?,?,?,?,?,?)",
        Json.id(),
        org,
        id,
        kind,
        date,
        Json.write(Map.of("reason", reason)));
    audit(org, "HR_" + kind, id);
    super.event(org, "HR_" + kind, Map.of("employee", id, "date", date.toString()));
    return get(org, id);
  }

  public Map<String, Object> absence(String org, Map<String, Object> p) {
    enter(org);
    String emp = Json.required(p, "employee");
    var e = employee(org, emp);
    ApiError.require("ACTIVE".equals(e.get("state")), "Сотрудник не работает");
    ApiError.require(
        access.has("hr") || access.has("admin") || access.user().equals(e.get("username")),
        "Можно подать заявку только за себя");
    LocalDate from = LocalDate.parse(Json.required(p, "from")),
        to = LocalDate.parse(Json.required(p, "to"));
    ApiError.require(
        !to.isBefore(from) && from.plusYears(1).isAfter(to), "Проверьте период отсутствия");
    String kind = Json.required(p, "kind");
    ApiError.require(
        Set.of("VACATION", "UNPAID", "TRIP", "SICK", "OTHER").contains(kind),
        "Неизвестный вид отсутствия");
    if (kind.equals("SICK")) access.role("hr");
    ApiError.require(
        db.queryForObject(
                "select count(*) from absence where org=? and employee_id=? and state<>'REJECTED'"
                    + " and starts<=? and ends>=?",
                Integer.class,
                org,
                emp,
                to,
                from)
            == 0,
        "Период пересекается с другим отсутствием");
    checkTimesheet(org, from, to);
    String id = Json.id();
    db.update(
        "insert into absence values(?,?,?,?,?,?,?,?)",
        id,
        org,
        emp,
        kind,
        from,
        to,
        kind.equals("SICK") ? "APPROVED" : "PENDING",
        "");
    audit(org, "ABSENCE_CREATED", id);
    super.event(org, "ABSENCE_REQUEST", Map.of("id", id, "employee", emp));
    return Map.of("id", id);
  }

  private void checkTimesheet(String org, LocalDate from, LocalDate to) {
    ApiError.require(
        db.queryForObject(
                "select count(*) from timesheet where org=? and closed=true and month>=? and"
                    + " month<=?",
                Integer.class,
                org,
                YearMonth.from(from).toString(),
                YearMonth.from(to).toString())
            == 0,
        "Табель за период закрыт");
  }

  public List<Map<String, Object>> absences(String org) {
    enter(org);
    var rows =
        db.queryForList(
            "select a.*,e.name,e.username from absence a join employee e on e.id=a.employee_id and"
                + " e.org=a.org where a.org=? order by a.starts desc limit 1000",
            org);
    return rows.stream()
        .filter(
            r ->
                access.has("admin")
                    || access.has("hr")
                    || access.has("manager")
                    || access.user().equals(r.get("username")))
        .toList();
  }

  public void decide(String org, String id, Map<String, Object> p) {
    enter(org);
    access.role("manager");
    var a = one("select * from absence where org=? and id=?", org, id);
    ApiError.require("PENDING".equals(a.get("state")), "Заявка уже рассмотрена");
    var e = employee(org, Json.str(a, "employee_id"));
    ApiError.require(!access.user().equals(e.get("username")), "Нельзя согласовать свою заявку");
    checkTimesheet(
        org,
        LocalDate.parse(a.get("starts").toString()),
        LocalDate.parse(a.get("ends").toString()));
    boolean ok = Boolean.TRUE.equals(p.get("approve"));
    String comment = Json.str(p, "comment");
    if (!ok) ApiError.require(!comment.isBlank(), "Укажите причину отказа");
    db.update(
        "update absence set state=?,comment=? where org=? and id=?",
        ok ? "APPROVED" : "REJECTED",
        comment,
        org,
        id);
    audit(org, "ABSENCE_DECIDED", id);
    super.event(org, "ABSENCE_DECISION", Map.of("id", id, "approved", ok));
  }

  public Map<String, Object> timesheet(String org, String month, boolean close) {
    enter(org);
    access.role("hr");
    YearMonth ym = YearMonth.parse(month);
    var old = db.queryForList("select * from timesheet where org=? and month=?", org, month);
    if (!old.isEmpty() && Boolean.TRUE.equals(old.getFirst().get("closed")))
      return unpack(old.getFirst(), "data");
    var days = new ArrayList<Map<String, Object>>();
    var calendar =
        db.queryForList("select data from master where org=? and kind='calendar'", org).stream()
            .map(r -> Json.read(r.get("data")))
            .toList();
    for (var e :
        db.queryForList(
            "select * from employee where org=? and hired_on is not null and hired_on<=? and"
                + " (fired_on is null or fired_on>=?)",
            org,
            ym.atEndOfMonth(),
            ym.atDay(1))) {
      var rows =
          db.queryForList(
              "select * from absence where org=? and employee_id=? and state='APPROVED'",
              org,
              e.get("id"));
      for (int i = 1; i <= ym.lengthOfMonth(); i++) {
        LocalDate day = ym.atDay(i);
        boolean employed =
            !day.isBefore(LocalDate.parse(e.get("hired_on").toString()))
                && (e.get("fired_on") == null
                    || !day.isAfter(LocalDate.parse(e.get("fired_on").toString())));
        boolean work = day.getDayOfWeek().getValue() <= 5;
        for (var c : calendar)
          if (day.toString().equals(c.get("date"))) work = Boolean.TRUE.equals(c.get("working"));
        String code = employed ? (work ? "WORK" : "OFF") : "NOT_EMPLOYED";
        for (var a : rows)
          if (employed
              && !day.isBefore(LocalDate.parse(a.get("starts").toString()))
              && !day.isAfter(LocalDate.parse(a.get("ends").toString())))
            code = Json.str(a, "kind");
        days.add(
            Map.of(
                "employee",
                e.get("id"),
                "name",
                e.get("name"),
                "date",
                day.toString(),
                "code",
                code,
                "hours",
                code.equals("WORK") ? 8 : 0));
      }
    }
    var payload =
        Map.of(
            "month",
            month,
            "days",
            days,
            "notice",
            "Проверьте производственный календарь перед закрытием");
    String id = old.isEmpty() ? Json.id() : Json.str(old.getFirst(), "id");
    if (old.isEmpty())
      db.update(
          "insert into timesheet values(?,?,?,?,?)", id, org, month, Json.write(payload), close);
    else
      db.update(
          "update timesheet set data=?,closed=? where org=? and id=?",
          Json.write(payload),
          close,
          org,
          id);
    if (close) {
      audit(org, "TIMESHEET_CLOSED", id);
      super.event(
          org,
          "ONE_C_EXPORT",
          Map.of(
              "timesheet",
              payload,
              "events",
              db.queryForList(
                  "select employee_id,kind,cast(event_date as varchar) event_date from hr_event"
                      + " where org=? and event_date>=? and event_date<=?",
                  org,
                  ym.atDay(1),
                  ym.atEndOfMonth())));
    }
    return Map.of("id", id, "closed", close, "data", payload);
  }
}
