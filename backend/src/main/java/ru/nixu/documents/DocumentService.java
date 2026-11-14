package ru.nixu.documents;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.nixu.core.*;

@Service
@Transactional
public class DocumentService extends Store {
  public static final Set<String> KINDS =
      Set.of(
          "REQ",
          "PO",
          "RECEIPT",
          "SO",
          "INVOICE",
          "SHIPMENT",
          "RETURN_IN",
          "RETURN_OUT",
          "TRANSFER",
          "WRITE_OFF",
          "ISSUE",
          "RETURN_EMP");
  private static final Set<String> STOCK =
      Set.of(
          "RECEIPT",
          "SHIPMENT",
          "RETURN_IN",
          "RETURN_OUT",
          "TRANSFER",
          "WRITE_OFF",
          "ISSUE",
          "RETURN_EMP");

  public DocumentService(JdbcTemplate d, Access a) {
    super(d, a);
  }

  private void writeRole(String kind) {
    switch (kind) {
      case "REQ" -> access.role("purchasing", "employee");
      case "PO" -> access.role("purchasing");
      case "SO", "INVOICE" -> access.role("sales");
      default -> access.role("warehouse");
    }
  }

  private void view(Map<String, Object> doc) {
    if (access.has("employee") && !access.has("admin") && !access.user().equals(doc.get("creator")))
      throw new ApiError(403, "Доступны только свои документы");
  }

  public Map<String, Object> raw(String org, String id) {
    var r = one("select * from document where org=? and id=?", org, id);
    view(r);
    return r;
  }

  public Map<String, Object> get(String org, String id) {
    enter(org);
    var r = unpack(raw(org, id), "payload");
    r.put(
        "payments",
        db.queryForList(
            "select * from payment where org=? and document_id=? order by paid_on", org, id));
    r.put(
        "children",
        db.queryForList(
            "select id,kind,number,state,total from document where org=? and source_id=?",
            org,
            id));
    r.put(
        "comments",
        db.queryForList(
            "select * from comment where org=? and document_id=? order by created_at", org, id));
    r.put(
        "attachments",
        db.queryForList(
            "select id,name,size_bytes from attachment where org=? and document_id=?", org, id));
    r.put(
        "movements",
        db.queryForList("select * from movement where org=? and document_id=?", org, id));
    return r;
  }

  public Map<String, Object> list(
      String org, String kind, String state, String search, int page, int size) {
    enter(org);
    size = Math.max(1, Math.min(size, 100));
    page = Math.max(0, page);
    String where =
        " where org=? and (?='' or kind=?) and (?='' or state=?) and lower(number) like ?";
    var args =
        new ArrayList<Object>(
            List.of(org, kind, kind, state, state, "%" + search.toLowerCase() + "%"));
    if (access.has("employee") && !access.has("admin")) {
      where += " and creator=?";
      args.add(access.user());
    }
    var count =
        db.queryForObject("select count(*) from document" + where, Long.class, args.toArray());
    args.add(size);
    args.add(page * size);
    var rows =
        db
            .queryForList(
                "select * from document" + where + " order by created_at desc,id limit ? offset ?",
                args.toArray())
            .stream()
            .map(r -> unpack(r, "payload"))
            .toList();
    return Map.of("items", rows, "total", count, "page", page, "size", size);
  }

  public Map<String, Object> save(String org, Map<String, Object> p, String idem) {
    enter(org);
    String kind = Json.required(p, "kind");
    ApiError.require(KINDS.contains(kind), "Неизвестный вид документа");
    writeRole(kind);
    LocalDate date = LocalDate.parse(Json.required(p, "date"));
    period(org, date);
    String currency = Json.str(p, "currency");
    if (currency.isBlank()) currency = "RUB";
    ApiError.require(currency.matches("[A-Z]{3}"), "Неверная валюта");
    var rate = rate(org, currency, date);
    String source = Json.str(p, "source");
    if (!source.isBlank()) raw(org, source);
    var lines = Json.lines(p);
    ApiError.require(!lines.isEmpty() && lines.size() <= 100, "Укажите от 1 до 100 строк");
    var net = BigDecimal.ZERO;
    var total = BigDecimal.ZERO;
    Set<String> seen = new HashSet<>();
    for (var line : lines) {
      String item = Json.required(line, "item");
      ApiError.require(seen.add(item), "Одна номенклатура — одна строка");
      var m = Json.read(master(org, item, "item").get("data"));
      var q = Json.dec(line.get("qty"));
      var price = Json.dec(line.get("price"));
      var discount = Json.dec(line.getOrDefault("discount", "0"));
      var vat = Json.dec(m.getOrDefault("vat", "0"));
      ApiError.require(
          q.signum() > 0 && q.scale() <= 3 && q.compareTo(new BigDecimal("1000000")) <= 0,
          "Количество должно быть положительным, не более 1 000 000, до 3 знаков");
      ApiError.require(
          price.signum() >= 0
              && price.compareTo(new BigDecimal("1000000000")) <= 0
              && discount.signum() >= 0
              && discount.compareTo(new BigDecimal("100")) <= 0,
          "Проверьте цену и скидку");
      if (Boolean.TRUE.equals(m.get("serial")))
        ApiError.require(
            q.stripTrailingZeros().scale() <= 0, "Серийный товар учитывается поштучно");
      var n =
          Json.money(
              q.multiply(price).multiply(BigDecimal.ONE.subtract(discount.movePointLeft(2))));
      var t = n.add(Json.money(n.multiply(vat.movePointLeft(2))));
      line.put("vat", vat);
      line.put("net", n);
      line.put("total", t);
      net = net.add(n);
      total = total.add(t);
    }
    String id = Json.str(p, "id");
    String fp = hash(Json.write(p));
    if (id.isBlank()) {
      ApiError.require(
          idem != null && idem.matches("[A-Za-z0-9_-]{8,100}"),
          "Нужен Idempotency-Key (8–100 символов)");
      var previous =
          db.queryForList(
              "select * from idempotency where org=? and actor=? and idem=?",
              org,
              access.user(),
              idem);
      if (!previous.isEmpty()) {
        if (!fp.equals(previous.getFirst().get("fingerprint")))
          throw new ApiError(409, "Этот ключ повтора уже использован с другим содержимым");
        return get(org, Json.str(previous.getFirst(), "result_id"));
      }
      id = Json.id();
      long seq =
          db.queryForObject(
                  "select count(*) from document where org=? and kind=? and doc_date>=? and"
                      + " doc_date<?",
                  Long.class,
                  org,
                  kind,
                  date.withDayOfYear(1),
                  date.withDayOfYear(1).plusYears(1))
              + 1;
      String number = kind + "-" + date.getYear() + "-" + String.format("%06d", seq);
      db.update(
          "insert into"
              + " document(id,org,kind,number,doc_date,state,creator,source_id,payload,total,net,currency,rate)"
              + " values(?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?)",
          id,
          org,
          kind,
          number,
          date,
          access.user(),
          source.isBlank() ? null : source,
          Json.write(p),
          total,
          net,
          currency,
          rate);
      db.update("insert into idempotency values(?,?,?,?,?)", org, access.user(), idem, fp, id);
    } else {
      var old = raw(org, id);
      ApiError.require("DRAFT".equals(old.get("state")), "Редактировать можно только черновик");
      period(org, LocalDate.parse(old.get("doc_date").toString()));
      ApiError.require(kind.equals(old.get("kind")), "Вид документа менять нельзя");
      int n =
          db.update(
              "update document set"
                  + " doc_date=?,source_id=?,payload=?,total=?,net=?,currency=?,rate=?,version=version+1"
                  + " where org=? and id=? and version=?",
              date,
              source.isBlank() ? null : source,
              Json.write(p),
              total,
              net,
              currency,
              rate,
              org,
              id,
              Json.dec(p.get("version")).intValueExact());
      if (n != 1) throw new ApiError(409, "Документ уже изменён. Обновите карточку");
    }
    audit(org, "DOCUMENT_SAVED", id);
    return get(org, id);
  }

  private String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> action(
      String org, String id, String action, int version, Map<String, Object> options) {
    enter(org);
    var doc = raw(org, id);
    String kind = Json.str(doc, "kind"), state = Json.str(doc, "state");
    period(org, LocalDate.parse(doc.get("doc_date").toString()));
    if (Json.dec(doc.get("version")).intValue() != version)
      throw new ApiError(409, "Документ изменён. Обновите карточку");
    switch (action) {
      case "submit" -> {
        writeRole(kind);
        ApiError.require(state.equals("DRAFT"), "На согласование отправляется черновик");
        state = "PENDING";
      }
      case "approve", "reject" -> {
        access.role("manager");
        ApiError.require(state.equals("PENDING"), "Документ не ожидает согласования");
        ApiError.require(
            !access.user().equals(doc.get("creator")), "Автор не может согласовать свой документ");
        if (action.equals("reject")) Json.required(options, "comment");
        state = action.equals("approve") ? "APPROVED" : "DRAFT";
        event(
            org,
            "APPROVAL_DECISION",
            Map.of("document", id, "recipient", doc.get("creator"), "decision", state));
      }
      case "post" -> {
        writeRole(kind);
        ApiError.require(
            state.equals("DRAFT") || state.equals("APPROVED"),
            "Недопустимое состояние для проведения");
        var payload = Json.read(doc.get("payload"));
        boolean approval = kind.equals("REQ");
        if (kind.equals("PO"))
          approval =
              Json.dec(doc.get("total"))
                      .multiply(Json.dec(doc.get("rate")))
                      .compareTo(threshold(org, doc))
                  > 0;
        if (kind.equals("WRITE_OFF"))
          approval = estimatedCost(org, payload).compareTo(threshold(org, doc)) > 0;
        if (kind.equals("SO"))
          approval =
              Json.lines(payload).stream()
                  .anyMatch(l -> Json.dec(l.get("discount")).compareTo(new BigDecimal("10")) > 0);
        ApiError.require(
            !approval || state.equals("APPROVED"), "Документ требует согласования руководителя");
        validateSource(org, doc);
        if (STOCK.contains(kind)) move(org, doc);
        state = "POSTED";
      }
      case "cancel" -> {
        writeRole(kind);
        ApiError.require(!state.equals("CANCELLED"), "Документ уже отменён");
        ApiError.require(
            db.queryForObject(
                    "select count(*) from document where org=? and source_id=? and"
                        + " state<>'CANCELLED'",
                    Integer.class,
                    org,
                    id)
                == 0,
            "Сначала отмените зависимые документы");
        ApiError.require(
            db.queryForObject(
                    "select count(*) from payment where org=? and document_id=?",
                    Integer.class,
                    org,
                    id)
                == 0,
            "Документ с оплатой нельзя отменить");
        ApiError.require(
            !state.equals("POSTED") || !STOCK.contains(kind),
            "Для проведённого складского документа оформите возврат/корректировку");
        db.update("delete from reservation where org=? and document_id=?", org, id);
        state = "CANCELLED";
      }
      default -> throw ApiError.bad("Неизвестная операция");
    }
    db.update("update document set state=?,version=version+1 where org=? and id=?", state, org, id);
    audit(org, "DOCUMENT_" + action.toUpperCase(), id);
    event(org, "DOCUMENT_" + state, Map.of("document", id, "actor", access.user()));
    return get(org, id);
  }

  private BigDecimal threshold(String org, Map<String, Object> doc) {
    var cfg = db.queryForList("select data from master where org=? and kind='settings'", org);
    var usd =
        cfg.isEmpty()
            ? new BigDecimal("1000")
            : Json.dec(Json.read(cfg.getFirst().get("data")).getOrDefault("approvalUSD", "1000"));
    return usd.multiply(rate(org, "USD", LocalDate.parse(doc.get("doc_date").toString())));
  }

  private void validateSource(String org, Map<String, Object> doc) {
    String kind = Json.str(doc, "kind");
    String required =
        switch (kind) {
          case "RECEIPT" -> "PO";
          case "SHIPMENT", "INVOICE" -> "SO";
          case "RETURN_IN" -> "SHIPMENT";
          case "RETURN_OUT" -> "RECEIPT";
          default -> "";
        };
    if (required.isBlank()) return;
    String source = Json.str(doc, "source_id");
    ApiError.require(!source.isBlank(), "Нужен документ-основание");
    var s = raw(org, source);
    ApiError.require(
        required.equals(s.get("kind")) && "POSTED".equals(s.get("state")),
        "Основание должно быть проведено и иметь вид " + required);
    ApiError.require(
        s.get("currency").equals(doc.get("currency")), "Валюта должна совпадать с основанием");
    if (kind.equals("INVOICE")) {
      BigDecimal used =
          db.queryForObject(
              "select coalesce(sum(total),0) from document where org=? and source_id=? and"
                  + " kind='INVOICE' and state='POSTED'",
              BigDecimal.class,
              org,
              source);
      ApiError.require(
          used.add(Json.dec(doc.get("total"))).compareTo(Json.dec(s.get("total"))) <= 0,
          "Счета превышают сумму заказа");
      return;
    }
    var sourceLines = Json.lines(Json.read(s.get("payload")));
    var other =
        db.queryForList(
            "select payload from document where org=? and source_id=? and kind=? and"
                + " state='POSTED'",
            org,
            source,
            kind);
    for (var l : Json.lines(Json.read(doc.get("payload")))) {
      String item = Json.str(l, "item");
      BigDecimal limit =
          sourceLines.stream()
              .filter(x -> item.equals(x.get("item")))
              .map(x -> Json.dec(x.get("qty")))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal used =
          other.stream()
              .flatMap(x -> Json.lines(Json.read(x.get("payload"))).stream())
              .filter(x -> item.equals(x.get("item")))
              .map(x -> Json.dec(x.get("qty")))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      ApiError.require(
          used.add(Json.dec(l.get("qty"))).compareTo(limit) <= 0,
          "Количество превышает остаток по основанию: " + item);
    }
  }

  private BigDecimal estimatedCost(String org, Map<String, Object> p) {
    BigDecimal result = BigDecimal.ZERO;
    for (var l : Json.lines(p)) {
      String item = Json.str(l, "item");
      var m = Json.read(master(org, item, "item").get("data"));
      if (Boolean.TRUE.equals(m.get("serial"))) {
        for (String sn : serials(l, true))
          result =
              result.add(Json.dec(stock(org, Json.str(p, "warehouse"), item, sn).get("value")));
      } else {
        var rows =
            db.queryForList(
                "select * from stock where org=? and warehouse=? and item=? and qty>0",
                org,
                Json.str(p, "warehouse"),
                item);
        var q =
            rows.stream().map(r -> Json.dec(r.get("qty"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        var v =
            rows.stream()
                .map(r -> Json.dec(r.get("value")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (q.signum() > 0)
          result =
              result.add(v.multiply(Json.dec(l.get("qty"))).divide(q, 2, RoundingMode.HALF_UP));
      }
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private List<String> serials(Map<String, Object> line, boolean serial) {
    if (!serial) return List.of("");
    var list = (List<String>) line.getOrDefault("serials", List.of());
    ApiError.require(
        list.size() == Json.dec(line.get("qty")).intValueExact()
            && new HashSet<>(list).size() == list.size()
            && list.stream().allMatch(s -> !s.isBlank() && s.length() <= 200),
        "Укажите уникальный серийный номер каждой единицы");
    return list;
  }

  private void move(String org, Map<String, Object> doc) {
    String id = Json.str(doc, "id"), kind = Json.str(doc, "kind");
    var p = Json.read(doc.get("payload"));
    String wh = Json.required(p, "warehouse");
    var whData = Json.read(master(org, wh, "warehouse").get("data"));
    ApiError.require(
        !Boolean.TRUE.equals(whData.get("locked")), "Склад заблокирован для инвентаризации");
    LocalDate date = LocalDate.parse(doc.get("doc_date").toString());
    var latest =
        db.queryForObject(
            "select max(doc_date) from movement where org=?", java.sql.Date.class, org);
    ApiError.require(
        latest == null || !date.isBefore(latest.toLocalDate()),
        "Движения задним числом запрещены в прототипе");
    String target = Json.str(p, "targetWarehouse");
    if (kind.equals("TRANSFER")) {
      ApiError.require(!target.equals(wh), "Выберите другой склад");
      var t = Json.read(master(org, target, "warehouse").get("data"));
      ApiError.require(!Boolean.TRUE.equals(t.get("locked")), "Склад-получатель заблокирован");
    }
    BigDecimal cost = BigDecimal.ZERO;
    for (var l : Json.lines(p)) {
      String item = Json.str(l, "item");
      var m = Json.read(master(org, item, "item").get("data"));
      if (!"GOODS".equals(m.get("type"))) continue;
      boolean serial = Boolean.TRUE.equals(m.get("serial"));
      for (String sn : serials(l, serial)) {
        BigDecimal qty = serial ? BigDecimal.ONE : Json.dec(l.get("qty"));
        if (kind.equals("ISSUE") || kind.equals("RETURN_EMP")) {
          var s = stock(org, wh, item, sn);
          String emp = Json.required(p, "employee");
          var employee = one("select * from employee where org=? and id=?", org, emp);
          ApiError.require(
              "ACTIVE".equals(employee.get("state")) || kind.equals("RETURN_EMP"),
              "Сотрудник не работает");
          ApiError.require(serial, "Выдача поддерживается для серийного оборудования");
          if (kind.equals("ISSUE")) {
            ApiError.require(
                Json.str(s, "employee").isBlank() && Json.dec(s.get("qty")).signum() > 0,
                "Оборудование уже выдано или отсутствует");
            ApiError.require(
                reserved(org, Json.str(s, "id"), "").signum() == 0, "Оборудование зарезервировано");
          } else
            ApiError.require(
                emp.equals(s.get("employee")), "Оборудование выдано другому сотруднику");
          db.update(
              "update stock set employee=? where org=? and id=?",
              kind.equals("ISSUE") ? emp : null,
              org,
              s.get("id"));
          continue;
        }
        boolean incoming = kind.equals("RECEIPT") || kind.equals("RETURN_IN");
        BigDecimal value;
        if (incoming) {
          value =
              kind.equals("RETURN_IN")
                  ? returnCost(org, doc, item, sn, qty)
                  : Json.money(
                      Json.dec(l.get("net"))
                          .multiply(Json.dec(doc.get("rate")))
                          .multiply(qty)
                          .divide(Json.dec(l.get("qty")), 8, RoundingMode.HALF_UP));
          adjust(org, wh, item, sn, qty, value, id, date);
        } else {
          var s = stock(org, wh, item, sn);
          BigDecimal available =
              Json.dec(s.get("qty"))
                  .subtract(
                      reserved(
                          org,
                          Json.str(s, "id"),
                          kind.equals("SHIPMENT") ? Json.str(doc, "source_id") : ""));
          ApiError.require(
              available.compareTo(qty) >= 0, "Недостаточный свободный остаток: " + item + " " + sn);
          ApiError.require(Json.str(s, "employee").isBlank(), "Оборудование выдано сотруднику");
          if (kind.equals("RETURN_OUT")) returnCost(org, doc, item, sn, qty);
          value =
              Json.money(
                  Json.dec(s.get("value"))
                      .multiply(qty)
                      .divide(Json.dec(s.get("qty")), 8, RoundingMode.HALF_UP));
          adjust(org, wh, item, sn, qty.negate(), value.negate(), id, date);
          if (kind.equals("TRANSFER")) adjust(org, target, item, sn, qty, value, id, date);
          if (kind.equals("SHIPMENT"))
            consumeReserve(org, Json.str(doc, "source_id"), Json.str(s, "id"), qty);
        }
        cost = cost.add(value);
      }
    }
    db.update("update document set cost=? where org=? and id=?", cost, org, id);
  }

  private BigDecimal returnCost(
      String org, Map<String, Object> doc, String item, String serial, BigDecimal qty) {
    var source =
        one(
            "select qty,value from movement where org=? and document_id=? and item=? and serial=?",
            org,
            doc.get("source_id"),
            item,
            serial);
    ApiError.require(
        Json.dec(source.get("qty")).abs().compareTo(qty) >= 0,
        "Серийный номер отсутствует в основании");
    return Json.money(
        Json.dec(source.get("value"))
            .abs()
            .multiply(qty)
            .divide(Json.dec(source.get("qty")).abs(), 8, RoundingMode.HALF_UP));
  }

  private Map<String, Object> stock(String org, String wh, String item, String sn) {
    return one(
        "select * from stock where org=? and warehouse=? and item=? and serial=?",
        org,
        wh,
        item,
        sn);
  }

  private BigDecimal reserved(String org, String stock, String except) {
    return db.queryForObject(
        "select coalesce(sum(qty),0) from reservation where org=? and stock_id=? and"
            + " document_id<>?",
        BigDecimal.class,
        org,
        stock,
        except);
  }

  private void adjust(
      String org,
      String wh,
      String item,
      String sn,
      BigDecimal qty,
      BigDecimal value,
      String doc,
      LocalDate date) {
    var rows =
        db.queryForList(
            "select * from stock where org=? and warehouse=? and item=? and serial=?",
            org,
            wh,
            item,
            sn);
    if (qty.signum() > 0 && !sn.isBlank())
      ApiError.require(
          db.queryForObject(
                  "select count(*) from stock where org=? and serial=? and qty>0",
                  Integer.class,
                  org,
                  sn)
              == 0,
          "Серийный номер уже есть на складе: " + sn);
    if (rows.isEmpty()) {
      ApiError.require(qty.signum() > 0, "Нет остатка " + item);
      db.update(
          "insert into stock(id,org,warehouse,item,serial,qty,value) values(?,?,?,?,?,?,?)",
          Json.id(),
          org,
          wh,
          item,
          sn,
          qty,
          value);
    } else {
      var s = rows.getFirst();
      ApiError.require(Json.dec(s.get("qty")).add(qty).signum() >= 0, "Недостаточный остаток");
      db.update(
          "update stock set qty=qty+?,value=value+? where id=? and org=?",
          qty,
          value,
          s.get("id"),
          org);
    }
    db.update(
        "insert into movement values(?,?,?,?,?,?,?,?,?)",
        Json.id(),
        org,
        doc,
        date,
        wh,
        item,
        sn,
        qty,
        value);
  }

  private void consumeReserve(String org, String order, String stock, BigDecimal qty) {
    var rows =
        db.queryForList(
            "select * from reservation where org=? and document_id=? and stock_id=?",
            org,
            order,
            stock);
    for (var r : rows) {
      var left = Json.dec(r.get("qty")).subtract(qty);
      if (left.signum() <= 0)
        db.update("delete from reservation where id=? and org=?", r.get("id"), org);
      else db.update("update reservation set qty=? where id=? and org=?", left, r.get("id"), org);
    }
  }

  public void reserve(String org, String id) {
    enter(org);
    access.role("sales");
    var doc = raw(org, id);
    ApiError.require(
        "SO".equals(doc.get("kind")) && "POSTED".equals(doc.get("state")),
        "Резерв создаётся для проведённого заказа клиента");
    ApiError.require(
        db.queryForObject(
                "select count(*) from reservation where org=? and document_id=?",
                Integer.class,
                org,
                id)
            == 0,
        "Резерв уже существует");
    var p = Json.read(doc.get("payload"));
    String wh = Json.required(p, "warehouse");
    for (var l : Json.lines(p)) {
      String item = Json.str(l, "item");
      if (!"GOODS".equals(Json.read(master(org, item, "item").get("data")).get("type"))) continue;
      BigDecimal shipped =
          db
              .queryForList(
                  "select payload from document where org=? and source_id=? and kind='SHIPMENT' and"
                      + " state='POSTED'",
                  org,
                  id)
              .stream()
              .flatMap(r -> Json.lines(Json.read(r.get("payload"))).stream())
              .filter(x -> item.equals(x.get("item")))
              .map(x -> Json.dec(x.get("qty")))
              .reduce(BigDecimal.ZERO, BigDecimal::add);
      BigDecimal need = Json.dec(l.get("qty")).subtract(shipped);
      for (var s :
          db.queryForList(
              "select * from stock where org=? and warehouse=? and item=? and qty>0 and employee is"
                  + " null order by serial",
              org,
              wh,
              item)) {
        var take = Json.dec(s.get("qty")).subtract(reserved(org, Json.str(s, "id"), "")).min(need);
        if (take.signum() > 0) {
          db.update(
              "insert into reservation values(?,?,?,?,?)", Json.id(), org, id, s.get("id"), take);
          need = need.subtract(take);
        }
      }
      ApiError.require(need.signum() == 0, "Недостаточно товара для резерва: " + item);
    }
    audit(org, "RESERVED", id);
  }

  public void release(String org, String id) {
    enter(org);
    access.role("sales");
    raw(org, id);
    db.update("delete from reservation where org=? and document_id=?", org, id);
    audit(org, "RESERVE_RELEASED", id);
  }

  public void pay(String org, String id, Map<String, Object> p, String idem) {
    enter(org);
    var doc = raw(org, id);
    String kind = Json.str(doc, "kind");
    ApiError.require(
        Set.of("PO", "INVOICE").contains(kind) && "POSTED".equals(doc.get("state")),
        "Оплата доступна для проведённого заказа поставщику/счёта");
    writeRole(kind);
    period(org, LocalDate.parse(doc.get("doc_date").toString()));
    LocalDate date = LocalDate.parse(Json.required(p, "date"));
    period(org, date);
    var amount = Json.money(Json.dec(p.get("amount")));
    ApiError.require(amount.signum() > 0, "Сумма должна быть положительной");
    ApiError.require(
        idem != null && idem.length() >= 8 && idem.length() <= 100, "Нужен Idempotency-Key");
    var previous = db.queryForList("select * from payment where org=? and idem=?", org, idem);
    if (!previous.isEmpty()) {
      var x = previous.getFirst();
      if (!id.equals(x.get("document_id"))
          || amount.compareTo(Json.dec(x.get("amount"))) != 0
          || !date.toString().equals(x.get("paid_on").toString()))
        throw new ApiError(409, "Ключ повтора использован для другой оплаты");
      return;
    }
    var paid =
        db.queryForObject(
            "select coalesce(sum(amount),0) from payment where org=? and document_id=?",
            BigDecimal.class,
            org,
            id);
    ApiError.require(
        paid.add(amount).compareTo(Json.dec(doc.get("total"))) <= 0,
        "Оплата превышает сумму документа");
    db.update("insert into payment values(?,?,?,?,?,?)", Json.id(), org, id, amount, date, idem);
    audit(org, "PAYMENT_RECORDED", id);
  }

  public void comment(String org, String id, String text) {
    enter(org);
    raw(org, id);
    ApiError.require(
        !text.isBlank() && text.length() <= 4000, "Комментарий: от 1 до 4000 символов");
    db.update(
        "insert into comment(id,org,document_id,author,body) values(?,?,?,?,?)",
        Json.id(),
        org,
        id,
        access.user(),
        text);
    audit(org, "COMMENT_ADDED", id);
  }

  public List<Map<String, Object>> stockReport(String org, String date) {
    enter(org);
    access.role("warehouse", "sales", "purchasing", "manager");
    if (date.isBlank())
      return db.queryForList(
          "select s.*,m.name,(select coalesce(sum(r.qty),0) from reservation r where r.org=s.org"
              + " and r.stock_id=s.id) reserved from stock s join master m on m.id=s.item and"
              + " m.org=s.org where s.org=? and s.qty>0 order by m.name,s.serial",
          org);
    return db.queryForList(
        "select warehouse,item,serial,sum(qty) qty,sum(value) value from movement where org=? and"
            + " doc_date<=? group by warehouse,item,serial having sum(qty)<>0 order by item,serial",
        org,
        LocalDate.parse(date));
  }

  public List<Map<String, Object>> movements(String org) {
    enter(org);
    access.role("warehouse", "manager");
    return db.queryForList(
        "select * from movement where org=? order by doc_date desc,id limit 1000", org);
  }

  public List<Map<String, Object>> profit(String org) {
    enter(org);
    access.role("profit");
    return db.queryForList(
        "select id,number,doc_date,net*rate revenue,cost,net*rate-cost profit from document where"
            + " org=? and kind='SHIPMENT' and state='POSTED' order by doc_date desc",
        org);
  }
}
