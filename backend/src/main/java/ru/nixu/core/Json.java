package ru.nixu.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.*;
import java.util.*;

public final class Json {
  private static final ObjectMapper M = new ObjectMapper();

  public static String write(Object x) {
    try {
      return M.writeValueAsString(x);
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> read(Object x) {
    try {
      return x instanceof Map ? (Map<String, Object>) x : M.readValue(String.valueOf(x), Map.class);
    } catch (Exception e) {
      throw ApiError.bad("Неверный формат JSON");
    }
  }

  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> lines(Map<String, Object> p) {
    return (List<Map<String, Object>>) p.getOrDefault("lines", List.of());
  }

  public static String str(Map<String, Object> p, String k) {
    return Objects.toString(p.get(k), "");
  }

  public static String required(Map<String, Object> p, String k) {
    String s = str(p, k).trim();
    ApiError.require(!s.isBlank() && s.length() <= 1000, "Заполните поле " + k);
    return s;
  }

  public static BigDecimal dec(Object x) {
    try {
      return new BigDecimal(Objects.toString(x, "0"));
    } catch (Exception e) {
      throw ApiError.bad("Ожидалось число");
    }
  }

  public static BigDecimal money(BigDecimal x) {
    return x.setScale(2, RoundingMode.HALF_UP);
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }
}
