package com.muze.config;

import com.muze.service.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 6 Configuration.
 * Configures stateless JWT authentication, BCrypt password hashing,
 * and permissive guest browsing with authenticated host room creation.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
                .authorizeHttpRequests(auth -> auth
                        // Static web assets
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        // Authentication REST API
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Music streaming & Audius discovery
                        .requestMatchers("/api/v1/audius/**").permitAll()
                        // Active rooms discovery & passcode verification
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/active").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/rooms/*/verify").permitAll()
                        // WebSockets: listeners and guests connect without restriction
                        .requestMatchers("/ws/**").permitAll()
                        // Observability & in-memory H2 DB Console
                        .requestMatchers("/actuator/**", "/h2-console/**").permitAll()
                        // Any other request allowed by default
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}