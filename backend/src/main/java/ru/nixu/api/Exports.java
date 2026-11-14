package ru.nixu.api;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ru.nixu.core.*;
import ru.nixu.documents.*;
import ru.nixu.hrm.*;

@RestController
@RequestMapping("/api/v1/orgs/{org}")
public class Exports {
  final HrService hr;
  final DocumentService docs;

  public Exports(HrService h, DocumentService d) {
    hr = h;
    docs = d;
  }

  @GetMapping("/export/{type}")
  ResponseEntity<byte[]> export(
      @PathVariable String org,
      @PathVariable String type,
      @RequestParam(defaultValue = "csv") String format,
      @RequestParam(defaultValue = "") String date)
      throws Exception {
    List<Map<String, Object>> rows =
        switch (type) {
          case "employees" -> hr.list(org);
          case "stock" -> docs.stockReport(org, date);
          case "profit" -> docs.profit(org);
          default -> throw ApiError.bad("Неизвестный отчёт");
        };
    var flat =
        rows.stream()
            .map(
                r -> {
                  var m = new LinkedHashMap<String, Object>(r);
                  Object data = m.remove("data");
                  if (data instanceof Map<?, ?> d) d.forEach((k, v) -> m.put(k.toString(), v));
                  return m;
                })
            .toList();
    var columns = new LinkedHashSet<String>();
    flat.forEach(r -> columns.addAll(r.keySet()));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String mime;
    if (format.equals("xlsx")) {
      mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      try (var wb = new XSSFWorkbook()) {
        var sheet = wb.createSheet("wax");
        var head = sheet.createRow(0);
        int c = 0;
        for (String k : columns) head.createCell(c++).setCellValue(k);
        int n = 1;
        for (var r : flat) {
          var row = sheet.createRow(n++);
          c = 0;
          for (String k : columns) row.createCell(c++).setCellValue(safe(r.get(k)));
        }
        sheet.createFreezePane(0, 1);
        for (c = 0; c < columns.size(); c++) sheet.setColumnWidth(c, 5500);
        wb.write(out);
      }
    } else {
      ApiError.require(format.equals("csv"), "Формат csv или xlsx");
      mime = "text/csv; charset=utf-8";
      out.write(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
      out.write((csv(new ArrayList<>(columns)) + "\r\n").getBytes(StandardCharsets.UTF_8));
      for (var r : flat)
        out.write(
            (csv(columns.stream().map(k -> safe(r.get(k))).toList()) + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=" + type + "." + format)
        .contentType(MediaType.parseMediaType(mime))
        .body(out.toByteArray());
  }

  private String safe(Object o) {
    String s = Objects.toString(o, "");
    return s.matches("^[=+@\\-\\t\\r].*") ? "'" + s : s;
  }

  private String csv(List<String> cells) {
    return cells.stream()
        .map(s -> "\"" + s.replace("\"", "\"\"") + "\"")
        .reduce((a, b) -> a + ";" + b)
        .orElse("");
  }

  @GetMapping("/documents/{id}/pdf")
  ResponseEntity<byte[]> pdf(@PathVariable String org, @PathVariable String id) throws Exception {
    var d = docs.get(org, id);
    var p = Json.read(d.get("payload"));
    var text = new ArrayList<String>();
    text.add("WAX — Workflows. Automation. eXecution.");
    text.add("Процессы. Автоматизация. Исполнение.");
    text.add("Документ " + d.get("number"));
    text.add("Дата: " + d.get("doc_date") + "    Состояние: " + d.get("state"));
    text.add("Юридическое лицо: " + org + "    Валюта: " + d.get("currency"));
    text.add("Курс к рублю: " + d.get("rate"));
    text.add("Основание: " + Objects.toString(d.get("source_id"), "—"));
    text.add("");
    for (var l : Json.lines(p)) {
      text.add(
          Json.str(l, "item")
              + " | "
              + l.get("qty")
              + " × "
              + l.get("price")
              + " | НДС "
              + l.get("vat")
              + "% | "
              + l.get("total"));
      if (l.containsKey("serials")) text.add("Серийные номера: " + l.get("serials"));
    }
    text.add("");
    text.add("Без НДС: " + d.get("net") + "    Итого: " + d.get("total"));
    text.add("Образец печатной формы прототипа. Не заменяет УПД и счёт-фактуру.");
    try (var pdf = new PDDocument();
        var out = new ByteArrayOutputStream()) {
      String fontPath =
          System.getenv()
              .getOrDefault(
                  "PDF_FONT",
                  System.getProperty("os.name").startsWith("Windows")
                      ? "C:/Windows/Fonts/arial.ttf"
                      : "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
      var font = PDType0Font.load(pdf, new File(fontPath));
      PDPageContentStream content = null;
      int y = 0;
      try {
        for (String line : text) {
          for (int offset = 0; offset < Math.max(1, line.length()); offset += 75) {
            if (y < 60) {
              if (content != null) content.close();
              var page = new PDPage(PDRectangle.A4);
              pdf.addPage(page);
              content = new PDPageContentStream(pdf, page);
              y = 790;
            }
            String part =
                line.substring(
                        Math.min(offset, line.length()), Math.min(offset + 75, line.length()))
                    .replaceAll("[\\p{Cntrl}]", " ");
            content.beginText();
            content.setFont(font, 10);
            content.newLineAtOffset(40, y);
            content.showText(part);
            content.endText();
            y -= 18;
          }
        }
      } finally {
        if (content != null) content.close();
      }
      pdf.save(out);
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_PDF)
          .header("Content-Disposition", "attachment; filename=" + d.get("number") + ".pdf")
          .body(out.toByteArray());
    }
  }

  @PostMapping("/employees/import")
  Object importEmployees(@PathVariable String org, @RequestBody String csv) {
    ApiError.require(csv.length() <= 2000000, "Максимальный размер импорта 2 МБ");
    var rows = parse(csv);
    ApiError.require(rows.size() > 1 && rows.size() <= 1001, "От 1 до 1000 строк");
    var header = rows.getFirst();
    var required =
        List.of(
            "First Name",
            "Last Name",
            "Birth Year",
            "Phone",
            "Home Address",
            "Employee Code",
            "Passport Number");
    ApiError.require(header.equals(required), "Заголовки должны соответствовать employees.csv");
    var known = new HashSet<String>();
    hr.list(org).forEach(e -> known.add(Json.str(e, "code")));
    var report = new ArrayList<Map<String, Object>>();
    for (int i = 1; i < rows.size(); i++) {
      var row = rows.get(i);
      try {
        ApiError.require(row.size() == 7, "Ожидалось 7 столбцов");
        String code = row.get(5);
        if (known.contains(code)) {
          report.add(Map.of("row", i + 1, "status", "SKIPPED", "message", "Код уже существует"));
          continue;
        }
        hr.save(
            org,
            new LinkedHashMap<>(
                Map.of(
                    "name",
                    row.get(1) + " " + row.get(0),
                    "code",
                    code,
                    "data",
                    Map.of(
                        "firstName",
                        row.get(0),
                        "lastName",
                        row.get(1),
                        "birthYear",
                        row.get(2),
                        "phone",
                        row.get(3),
                        "address",
                        row.get(4),
                        "passport",
                        row.get(6)))));
        known.add(code);
        report.add(Map.of("row", i + 1, "status", "ACCEPTED", "message", "Карточка не заполнена"));
      } catch (ApiError e) {
        report.add(Map.of("row", i + 1, "status", "REJECTED", "message", e.getMessage()));
      }
    }
    return report;
  }

  private List<List<String>> parse(String csv) {
    var rows = new ArrayList<List<String>>();
    var row = new ArrayList<String>();
    var s = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < csv.length(); i++) {
      char c = csv.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
          s.append('"');
          i++;
        } else quoted = !quoted;
      } else if (c == ',' && !quoted) {
        row.add(s.toString());
        s.setLength(0);
      } else if (c == '\n' && !quoted) {
        row.add(s.toString().stripTrailing());
        s.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
      } else if (c != '\r' || quoted) s.append(c);
    }
    ApiError.require(!quoted, "Незакрытые кавычки CSV");
    if (!row.isEmpty() || !s.isEmpty()) {
      row.add(s.toString());
      rows.add(row);
    }
    if (!rows.isEmpty() && !rows.getFirst().isEmpty())
      rows.getFirst().set(0, rows.getFirst().getFirst().replace("\uFEFF", ""));
    return rows;
  }
}
