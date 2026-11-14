package ru.nixu.api;

import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.nixu.core.*;
import ru.nixu.documents.*;
import ru.nixu.hrm.*;
import ru.nixu.mdm.*;

@RestController
@RequestMapping("/api/v1")
public class Api {
  final Access access;
  final MasterService mdm;
  final DocumentService docs;
  final HrService hr;
  final FilesService files;

  public Api(Access a, MasterService m, DocumentService d, HrService h, FilesService f) {
    access = a;
    mdm = m;
    docs = d;
    hr = h;
    files = f;
  }

  @GetMapping("/me")
  Object me() {
    return access.me();
  }

  @GetMapping("/grants")
  Object grants() {
    return mdm.grants();
  }

  @PostMapping("/grants")
  void grant(@RequestBody Map<String, Object> p) {
    mdm.grant(p);
  }

  @GetMapping("/orgs/{org}/masters")
  Object masters(@PathVariable String org, @RequestParam String kind) {
    return mdm.list(org, kind);
  }

  @PostMapping("/orgs/{org}/masters")
  Object master(@PathVariable String org, @RequestBody Map<String, Object> p) {
    return mdm.save(org, p);
  }

  @PostMapping("/orgs/{org}/period")
  void period(@PathVariable String org, @RequestBody Map<String, Object> p) {
    mdm.closePeriod(org, Json.required(p, "until"));
  }

  @DeleteMapping("/orgs/{org}/period")
  void periodOpen(@PathVariable String org) {
    mdm.openPeriod(org);
  }

  @GetMapping("/orgs/{org}/audit")
  Object audit(@PathVariable String org) {
    return mdm.auditLog(org);
  }

  @GetMapping("/orgs/{org}/outbox")
  Object outbox(@PathVariable String org) {
    return mdm.outbox(org);
  }

  @GetMapping("/orgs/{org}/documents")
  Object documents(
      @PathVariable String org,
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    return docs.list(org, kind, state, search, page, size);
  }

  @GetMapping("/orgs/{org}/documents/{id}")
  Object document(@PathVariable String org, @PathVariable String id) {
    return docs.get(org, id);
  }

  @PostMapping("/orgs/{org}/documents")
  Object save(
      @PathVariable String org,
      @RequestBody Map<String, Object> p,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return docs.save(org, p, key);
  }

  @PostMapping("/orgs/{org}/documents/{id}/actions/{action}")
  Object action(
      @PathVariable String org,
      @PathVariable String id,
      @PathVariable String action,
      @RequestBody Map<String, Object> p) {
    return docs.action(org, id, action, Json.dec(p.get("version")).intValueExact(), p);
  }

  @PostMapping("/orgs/{org}/documents/{id}/reserve")
  void reserve(@PathVariable String org, @PathVariable String id) {
    docs.reserve(org, id);
  }

  @DeleteMapping("/orgs/{org}/documents/{id}/reserve")
  void release(@PathVariable String org, @PathVariable String id) {
    docs.release(org, id);
  }

  @PostMapping("/orgs/{org}/documents/{id}/payments")
  void pay(
      @PathVariable String org,
      @PathVariable String id,
      @RequestBody Map<String, Object> p,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    docs.pay(org, id, p, key);
  }

  @PostMapping("/orgs/{org}/documents/{id}/comments")
  void comment(
      @PathVariable String org, @PathVariable String id, @RequestBody Map<String, Object> p) {
    docs.comment(org, id, Json.required(p, "text"));
  }

  @PostMapping("/orgs/{org}/documents/{id}/files")
  Object upload(
      @PathVariable String org, @PathVariable String id, @RequestParam("file") MultipartFile file)
      throws Exception {
    return files.upload(org, id, file);
  }

  @GetMapping("/orgs/{org}/files/{id}")
  ResponseEntity<byte[]> download(@PathVariable String org, @PathVariable String id)
      throws Exception {
    var m = files.metadata(org, id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(Json.str(m, "name"), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(files.download(org, id));
  }

  @GetMapping("/orgs/{org}/stock")
  Object stock(@PathVariable String org, @RequestParam(defaultValue = "") String date) {
    return docs.stockReport(org, date);
  }

  @GetMapping("/orgs/{org}/movements")
  Object movements(@PathVariable String org) {
    return docs.movements(org);
  }

  @GetMapping("/orgs/{org}/profit")
  Object profit(@PathVariable String org) {
    return docs.profit(org);
  }

  @GetMapping("/orgs/{org}/employees")
  Object employees(@PathVariable String org) {
    return hr.list(org);
  }

  @GetMapping("/orgs/{org}/employees/{id}")
  Object employee(@PathVariable String org, @PathVariable String id) {
    return hr.get(org, id);
  }

  @PostMapping("/orgs/{org}/employees")
  Object saveEmployee(@PathVariable String org, @RequestBody Map<String, Object> p) {
    return hr.save(org, p);
  }

  @PostMapping("/orgs/{org}/employees/{id}/events")
  Object event(
      @PathVariable String org, @PathVariable String id, @RequestBody Map<String, Object> p) {
    return hr.event(org, id, p);
  }

  @GetMapping("/orgs/{org}/absences")
  Object absences(@PathVariable String org) {
    return hr.absences(org);
  }

  @PostMapping("/orgs/{org}/absences")
  Object absence(@PathVariable String org, @RequestBody Map<String, Object> p) {
    return hr.absence(org, p);
  }

  @PostMapping("/orgs/{org}/absences/{id}/decision")
  void decide(
      @PathVariable String org, @PathVariable String id, @RequestBody Map<String, Object> p) {
    hr.decide(org, id, p);
  }

  @PostMapping("/orgs/{org}/timesheets/{month}")
  Object timesheet(
      @PathVariable String org, @PathVariable String month, @RequestBody Map<String, Object> p) {
    return hr.timesheet(org, month, Boolean.TRUE.equals(p.get("close")));
  }
}
