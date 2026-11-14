package ru.nixu.core;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class Access {
  private final JdbcTemplate db;

  public Access(JdbcTemplate db) {
    this.db = db;
  }

  public String user() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null) throw new ApiError(401, "Требуется вход");
    return a.getPrincipal() instanceof Jwt j
        ? j.getClaimAsString("preferred_username")
        : a.getName();
  }

  public Map<String, Object> grant() {
    var r = db.queryForList("select * from access_grant where username=? and enabled=true", user());
    if (r.isEmpty()) throw new ApiError(403, "Учётная запись заблокирована или не назначены права");
    return r.getFirst();
  }

  public boolean has(String role) {
    return Arrays.asList(Json.str(grant(), "roles").split(",")).contains(role);
  }

  public void role(String... roles) {
    if (has("admin")) return;
    for (String r : roles) if (has(r)) return;
    throw new ApiError(403, "Недостаточно прав для операции");
  }

  public void org(String org) {
    if (!Arrays.asList(Json.str(grant(), "orgs").split(",")).contains(org))
      throw new ApiError(403, "Нет доступа к юридическому лицу");
  }

  public Map<String, Object> me() {
    var g = grant();
    return Map.of(
        "username",
        user(),
        "roles",
        Json.str(g, "roles").split(","),
        "organizations",
        db.queryForList("select * from organizations").stream()
            .filter(o -> Arrays.asList(Json.str(g, "orgs").split(",")).contains(o.get("id")))
            .toList());
  }
}
