package ru.nixu;

import static org.junit.jupiter.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import ru.nixu.core.*;
import ru.nixu.documents.*;
import ru.nixu.hrm.*;
import ru.nixu.mdm.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=${TEST_DB_URL:jdbc:h2:mem:nixutest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=VALUE,MONTH}",
      "spring.datasource.username=${TEST_DB_USER:sa}",
      "spring.datasource.password=${TEST_DB_PASSWORD:}",
      "spring.flyway.enabled=true",
      "nixu.demo-password=Test-only-password-42"
    })
@org.springframework.test.context.TestExecutionListeners(
    value = org.springframework.test.context.support.DependencyInjectionTestExecutionListener.class,
    inheritListeners = false)
@ActiveProfiles("test")
class WorkflowTest {
  @Autowired DocumentService docs;
  @Autowired MasterService mdm;
  @Autowired HrService hr;
  @Autowired JdbcTemplate db;
  final String date = LocalDate.now().toString();

  void as(String name) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(name, "", List.of()));
  }

  @BeforeEach
  void clean() {
    as("admin");
    for (String table :
        List.of(
            "reservation",
            "payment",
            "attachment",
            "comment",
            "movement",
            "stock",
            "idempotency",
            "document",
            "absence",
            "hr_event",
            "employee",
            "timesheet",
            "periods",
            "audit",
            "outbox")) db.update("delete from " + table);
    db.update("update access_grant set enabled=true");
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  Map<String, Object> payload(String kind, String source, int qty, int price, String... serials) {
    return Json.read(
        Json.write(
            Map.of(
                "kind",
                kind,
                "date",
                date,
                "currency",
                "USD",
                "source",
                source,
                "warehouse",
                "wh-main",
                "lines",
                List.of(
                    Map.of(
                        "item",
                        "server",
                        "qty",
                        qty,
                        "price",
                        price,
                        "serials",
                        List.of(serials))))));
  }

  Map<String, Object> create(Map<String, Object> p) {
    return docs.save("demo", p, Json.id());
  }

  Map<String, Object> action(Map<String, Object> d, String a) {
    return docs.action(
        "demo", Json.str(d, "id"), a, Json.dec(d.get("version")).intValue(), Map.of());
  }

  Map<String, Object> purchase(int qty, int price, String... serials) {
    as("purchasing");
    var po = create(payload("PO", "", qty, price));
    po = action(po, "submit");
    as("manager");
    po = action(po, "approve");
    as("purchasing");
    po = action(po, "post");
    as("warehouse");
    return action(create(payload("RECEIPT", Json.str(po, "id"), qty, price, serials)), "post");
  }

  @Test
  void purchaseSaleReservationPartialPaymentAndProfit() {
    purchase(5, 1000, "A1", "A2", "A3", "A4", "A5");
    as("sales");
    var order = action(create(payload("SO", "", 3, 1500)), "post");
    docs.reserve("demo", Json.str(order, "id"));
    assertEquals(3, db.queryForObject("select count(*) from reservation", Integer.class));
    var invoice = action(create(payload("INVOICE", Json.str(order, "id"), 3, 750)), "post");
    String key = Json.id();
    docs.pay("demo", Json.str(invoice, "id"), Map.of("date", date, "amount", "2700"), key);
    docs.pay("demo", Json.str(invoice, "id"), Map.of("date", date, "amount", "2700"), key);
    assertEquals(1, db.queryForObject("select count(*) from payment", Integer.class));
    as("warehouse");
    var shipment =
        action(
            create(payload("SHIPMENT", Json.str(order, "id"), 3, 1500, "A1", "A2", "A3")), "post");
    assertEquals(0, new BigDecimal("270000").compareTo(Json.dec(shipment.get("cost"))));
    assertEquals(
        0,
        new BigDecimal("2")
            .compareTo(db.queryForObject("select sum(qty) from stock", BigDecimal.class)));
    assertEquals(0, db.queryForObject("select count(*) from reservation", Integer.class));
    as("sales");
    assertEquals(
        0,
        new BigDecimal("135000").compareTo(Json.dec(docs.profit("demo").getFirst().get("profit"))));
  }

  @Test
  void idempotencyAndOptimisticLock() {
    as("purchasing");
    String key = Json.id();
    var p = payload("PO", "", 1, 100);
    var a = docs.save("demo", p, key);
    var b = docs.save("demo", p, key);
    assertEquals(a.get("id"), b.get("id"));
    assertEquals(
        409,
        assertThrows(ApiError.class, () -> docs.save("demo", payload("PO", "", 2, 100), key))
            .status);
    p.put("id", a.get("id"));
    p.put("version", 0);
    docs.save("demo", p, null);
    assertEquals(409, assertThrows(ApiError.class, () -> docs.save("demo", p, null)).status);
  }

  @Test
  void approvalPeriodAndImmutability() {
    as("purchasing");
    var p = create(payload("PO", "", 2, 1000));
    assertThrows(ApiError.class, () -> action(p, "post"));
    var pending = action(p, "submit");
    as("manager");
    var approved = action(pending, "approve");
    as("purchasing");
    var posted = action(approved, "post");
    var edit = payload("PO", "", 1, 1);
    edit.put("id", posted.get("id"));
    edit.put("version", posted.get("version"));
    assertThrows(ApiError.class, () -> docs.save("demo", edit, null));
    as("admin");
    mdm.closePeriod("demo", date);
    assertThrows(ApiError.class, () -> create(payload("PO", "", 1, 10)));
  }

  @Test
  void organizationAndRoleBoundaries() {
    as("purchasing");
    var d = create(payload("PO", "", 1, 100));
    as("sales");
    assertThrows(ApiError.class, () -> create(payload("PO", "", 1, 100)));
    assertEquals(
        403, assertThrows(ApiError.class, () -> docs.get("second", Json.str(d, "id"))).status);
    as("employee");
    assertEquals(
        403, assertThrows(ApiError.class, () -> docs.get("demo", Json.str(d, "id"))).status);
  }

  @Test
  void postingIsAtomicAndNoNegativeStock() {
    purchase(2, 10, "B1", "B2");
    as("sales");
    var so = action(create(payload("SO", "", 2, 20)), "post");
    as("warehouse");
    var bad = create(payload("SHIPMENT", Json.str(so, "id"), 2, 20, "B1", "MISSING"));
    int before = db.queryForObject("select count(*) from movement", Integer.class);
    assertThrows(ApiError.class, () -> action(bad, "post"));
    assertEquals(before, db.queryForObject("select count(*) from movement", Integer.class));
    assertEquals(
        0,
        new BigDecimal("2")
            .compareTo(db.queryForObject("select sum(qty) from stock", BigDecimal.class)));
    assertEquals("DRAFT", docs.get("demo", Json.str(bad, "id")).get("state"));
  }

  @Test
  void serializedStockCannotBeSpentTwiceConcurrently() throws Exception {
    purchase(1, 10, "RACE");
    as("warehouse");
    var a = create(payload("WRITE_OFF", "", 1, 10, "RACE"));
    var b = create(payload("WRITE_OFF", "", 1, 10, "RACE"));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = pool.invokeAll(List.<Callable<Boolean>>of(() -> attempt(a), () -> attempt(b)));
      int successes = 0;
      for (var f : futures) if (f.get()) successes++;
      assertEquals(1, successes);
    }
    assertEquals(0, db.queryForObject("select sum(qty) from stock", BigDecimal.class).signum());
  }

  boolean attempt(Map<String, Object> d) {
    as("warehouse");
    try {
      action(d, "post");
      return true;
    } catch (ApiError e) {
      return false;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  Map<String, Object> person(String code, String username) {
    return Json.read(
        "{\"name\":\"Тестовый сотрудник\",\"code\":\""
            + code
            + "\",\"username\":\""
            + username
            + "\",\"data\":{\"firstName\":\"Тест\",\"lastName\":\"Сотрудник\",\"noMiddleName\":true,\"birthDate\":\"1990-01-01\",\"phone\":\"000\",\"address\":\"Тестовый"
            + " адрес\",\"passport\":\"TEST-PASSPORT\",\"department\":\"department\",\"position\":\"position\",\"manager\":\"manager\"}}");
  }

  @Test
  void employeeLifecyclePrivacyAbsencesAndTimesheet() {
    as("hr");
    var e = hr.save("demo", person("TEST-001", "employee"));
    String id = Json.str(e, "id");
    hr.event("demo", id, Map.of("kind", "HIRE", "date", date, "reason", "Тестовый приказ"));
    as("employee");
    assertFalse(Json.read(hr.get("demo", id).get("data")).containsKey("passport"));
    var absence =
        hr.absence("demo", Map.of("employee", id, "kind", "VACATION", "from", date, "to", date));
    as("manager");
    hr.decide("demo", Json.str(absence, "id"), Map.of("approve", true));
    as("hr");
    assertEquals(true, hr.timesheet("demo", date.substring(0, 7), true).get("closed"));
    assertEquals(
        1,
        db.queryForObject("select count(*) from outbox where topic='ONE_C_EXPORT'", Integer.class));
    hr.event("demo", id, Map.of("kind", "FIRE", "date", date, "reason", "Тестовый приказ"));
    assertEquals(
        false,
        db.queryForObject(
            "select enabled from access_grant where username='employee'", Boolean.class));
  }

  @Test
  void issuingEquipmentBlocksDismissal() {
    purchase(1, 10, "LAPTOP");
    as("hr");
    String id = Json.str(hr.save("demo", person("T2", "")), "id");
    hr.event("demo", id, Map.of("kind", "HIRE", "date", date, "reason", "Тест"));
    as("warehouse");
    var p = payload("ISSUE", "", 1, 10, "LAPTOP");
    p.put("employee", id);
    action(create(p), "post");
    as("hr");
    assertThrows(
        ApiError.class,
        () -> hr.event("demo", id, Map.of("kind", "FIRE", "date", date, "reason", "Тест")));
    as("warehouse");
    p.put("kind", "RETURN_EMP");
    action(create(p), "post");
    as("hr");
    hr.event("demo", id, Map.of("kind", "FIRE", "date", date, "reason", "Тест"));
  }

  @org.springframework.beans.factory.annotation.Value("${local.server.port}")
  int port;

  @Test
  void httpLoginDownloadsAndDeniedAccess() throws Exception {
    var client = java.net.http.HttpClient.newHttpClient();
    String base = "http://127.0.0.1:" + port + "/api/v1";
    String auth =
        "Basic " + Base64.getEncoder().encodeToString("admin:Test-only-password-42".getBytes());
    var anonymous =
        client.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/me")).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(401, anonymous.statusCode());
    var logged =
        client.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/me"))
                .header("Authorization", auth)
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(200, logged.statusCode());
    assertTrue(logged.body().contains("admin"));
    as("admin");
    var doc = create(payload("PO", "", 1, 10));
    var detail =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(base + "/orgs/demo/documents/" + doc.get("id")))
                .header("Authorization", auth)
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(date, Json.read(detail.body()).get("doc_date"));
    var pdf =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(base + "/orgs/demo/documents/" + doc.get("id") + "/pdf"))
                .header("Authorization", auth)
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, pdf.statusCode());
    assertEquals("%PDF", new String(pdf.body(), 0, 4));
    var xlsx =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(base + "/orgs/demo/export/employees?format=xlsx"))
                .header("Authorization", auth)
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    assertEquals(200, xlsx.statusCode());
    assertEquals("PK", new String(xlsx.body(), 0, 2));
    String userAuth =
        "Basic " + Base64.getEncoder().encodeToString("employee:Test-only-password-42".getBytes());
    var denied =
        client.send(
            java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create(base + "/orgs/second/documents"))
                .header("Authorization", userAuth)
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
    assertEquals(403, denied.statusCode());
    assertTrue(
        db.queryForObject(
                "select count(*) from audit where action='REQUEST_REJECTED'", Integer.class)
            > 0);
  }

  @Test
  void approvalUsesSelectedSerialCost() {
    purchase(1, 1500, "EXPENSIVE");
    purchase(1, 10, "CHEAP");
    as("warehouse");
    var d = create(payload("WRITE_OFF", "", 1, 0, "EXPENSIVE"));
    assertThrows(ApiError.class, () -> action(d, "post"));
  }
}
