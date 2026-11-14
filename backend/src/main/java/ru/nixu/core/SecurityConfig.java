package ru.nixu.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("!migrate")
public class SecurityConfig {
  private HttpSecurity common(HttpSecurity h) throws Exception {
    return h.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health", "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated());
  }

  @Bean
  @Profile("!demo & !test")
  SecurityFilterChain oidc(HttpSecurity h) throws Exception {
    return common(h).oauth2ResourceServer(o -> o.jwt(j -> {})).build();
  }

  @Bean
  @Profile("!demo & !test")
  JwtDecoder decoder(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
    NimbusJwtDecoder d = JwtDecoders.fromIssuerLocation(issuer);
    d.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            jwt ->
                jwt.getAudience().contains("nixu-api")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
    return d;
  }

  @Bean
  @Profile("demo | test")
  SecurityFilterChain demo(HttpSecurity h) throws Exception {
    return common(h).httpBasic(b -> {}).build();
  }

  @Bean
  @Profile("demo | test")
  UserDetailsService demoUsers(@Value("${nixu.demo-password}") String password) {
    if (password.length() < 12)
      throw new IllegalStateException("DEMO_PASSWORD: минимум 12 символов");
    var encoder = new BCryptPasswordEncoder();
    var users = new InMemoryUserDetailsManager();
    for (String u :
        new String[] {"admin", "manager", "purchasing", "sales", "warehouse", "hr", "employee"})
      users.createUser(
          User.withUsername(u)
              .password("{bcrypt}" + encoder.encode(password))
              .roles("USER")
              .build());
    return users;
  }
}
