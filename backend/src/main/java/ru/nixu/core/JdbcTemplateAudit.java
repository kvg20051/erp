package ru.nixu.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component
public class JdbcTemplateAudit {
  private final JdbcTemplate db;

  public JdbcTemplateAudit(JdbcTemplate d) {
    db = d;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void denied(String user, String uri, int status, String id) {
    db.update(
        "insert into audit(id,actor,action,object_id,detail,request_id) values(?,?,?,?,?,?)",
        Json.id(),
        user == null ? "anonymous" : user,
        "REQUEST_REJECTED",
        uri.length() > 100 ? uri.substring(0, 100) : uri,
        "HTTP " + status,
        id);
  }
}
