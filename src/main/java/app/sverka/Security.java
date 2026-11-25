package app.sverka;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class Security {
    static final String DEMO_LOGIN = "demo@sverka.test";

    @Bean
    UserDetailsService users(@Value("${sverka.demo}") boolean demo, @Value("${sverka.user}") String username,
                             @Value("${sverka.password}") String password) {
        if (demo) {
            username = DEMO_LOGIN;
            password = "DemoSverka2026!";
        }
        if (username.isBlank() && password.isBlank()) return new InMemoryUserDetailsManager();
        if (username.isBlank() || username.length() > 254 || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Задайте SVERKA_USER и пароль от 12 символов, до 72 байт.");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username.strip().toLowerCase(Locale.ROOT))
            .password(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password)).roles("OPERATOR").build());
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(routes -> routes.requestMatchers("/login", "/assets/**", "/error").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true))
            .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")))
            .build();
    }
}
