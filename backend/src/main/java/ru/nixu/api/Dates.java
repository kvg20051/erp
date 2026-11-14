package ru.nixu.api;

import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class Dates implements ResponseBodyAdvice<Object> {
  public boolean supports(MethodParameter m, Class<? extends HttpMessageConverter<?>> c) {
    return true;
  }

  public Object beforeBodyWrite(
      Object body,
      MethodParameter m,
      MediaType t,
      Class<? extends HttpMessageConverter<?>> c,
      ServerHttpRequest req,
      ServerHttpResponse res) {
    return normalize(body);
  }

  private Object normalize(Object x) {
    if (x instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (x instanceof java.sql.Timestamp t) return t.toInstant().toString();
    if (x instanceof java.time.OffsetDateTime t) return t.toInstant().toString();
    if (x instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((k, v) -> result.put(k.toString(), normalize(v)));
      return result;
    }
    if (x instanceof List<?> list) return list.stream().map(this::normalize).toList();
    return x;
  }
}
