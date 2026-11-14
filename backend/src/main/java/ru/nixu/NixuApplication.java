package ru.nixu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NixuApplication {
  public static void main(String[] args) {
    var context = SpringApplication.run(NixuApplication.class, args);
    if (context.getEnvironment().matchesProfiles("migrate")) context.close();
  }
}
