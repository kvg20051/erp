package ru.nixu.documents;

import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.nixu.core.*;

@Service
@Transactional
public class FilesService extends Store {
  private final Path root;
  private final DocumentService docs;

  public FilesService(
      JdbcTemplate d, Access a, DocumentService ds, @Value("${nixu.files}") String path) {
    super(d, a);
    root = Path.of(path).toAbsolutePath().normalize();
    docs = ds;
  }

  public Map<String, Object> upload(String org, String document, MultipartFile file)
      throws Exception {
    enter(org);
    docs.raw(org, document);
    ApiError.require(
        file.getSize() > 0 && file.getSize() <= 10485760, "Файл должен быть не больше 10 МБ");
    String id = Json.id();
    Files.createDirectories(root);
    Files.copy(file.getInputStream(), root.resolve(id));
    String name =
        Objects.toString(file.getOriginalFilename(), "attachment").replaceAll("[\\r\\n\\\\/]", "_");
    if (name.length() > 200) name = name.substring(name.length() - 200);
    db.update(
        "insert into attachment(id,org,document_id,name,size_bytes) values(?,?,?,?,?)",
        id,
        org,
        document,
        name,
        file.getSize());
    audit(org, "ATTACHMENT_UPLOADED", id);
    return Map.of("id", id);
  }

  public Map<String, Object> metadata(String org, String id) {
    enter(org);
    var r = one("select * from attachment where org=? and id=?", org, id);
    docs.raw(org, Json.str(r, "document_id"));
    return r;
  }

  public byte[] download(String org, String id) throws Exception {
    metadata(org, id);
    audit(org, "ATTACHMENT_READ", id);
    return Files.readAllBytes(root.resolve(UUID.fromString(id).toString()));
  }
}
