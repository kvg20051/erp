package ru.nixu.core;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  private final JdbcTemplateAudit audit;

  public Errors(JdbcTemplateAudit a) {
    audit = a;
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> error(Exception e, HttpServletRequest r) {
    int status =
        e instanceof ApiError a
            ? a.status
            : e instanceof DataIntegrityViolationException
                ? 409
                : (e instanceof IllegalArgumentException
                        || e instanceof java.time.DateTimeException)
                    ? 422
                    : (e
                            instanceof
                            org.springframework.http.converter.HttpMessageNotReadableException
                        ? 400
                        : 500);
    String detail =
        e instanceof ApiError
            ? e.getMessage()
            : status == 409
                ? "Запись уже существует или связана с другими данными"
                : (status == 422 || status == 400)
                    ? "Проверьте формат полей"
                    : "Внутренняя ошибка. Сообщите администратору requestId.";
    String id = UUID.randomUUID().toString();
    if (status >= 500)
      LoggerFactory.getLogger(Errors.class)
          .error("requestId={} type={}", id, e.getClass().getName());
    try {
      audit.denied(r.getRemoteUser(), r.getRequestURI(), status, id);
    } catch (RuntimeException auditFailure) {
      LoggerFactory.getLogger(Errors.class).warn("Audit unavailable requestId={}", id);
    }
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            Map.of(
                "type",
                "about:blank",
                "title",
                "Ошибка запроса",
                "status",
                status,
                "detail",
                detail,
                "instance",
                r.getRequestURI(),
                "requestId",
                id));
  }
}
