package com.railfeas.auth;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final OAuth2UserService oAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final JwtAuthenticationEntryPoint entryPoint;
    private final JwtAccessDeniedHandler deniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          OAuth2UserService oAuth2UserService,
                          OAuth2SuccessHandler oAuth2SuccessHandler,
                          JwtAuthenticationEntryPoint entryPoint,
                          JwtAccessDeniedHandler deniedHandler) {
        this.jwtFilter = jwtFilter;
        this.oAuth2UserService = oAuth2UserService;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
        this.entryPoint = entryPoint;
        this.deniedHandler = deniedHandler;
    }

    /**
     * 소셜 로그인은 클라이언트 키가 있을 때만 켠다.
     * 키 없이 oauth2Login 을 걸면 ClientRegistrationRepository 가 없어 기동이 막힌다.
     * 키를 채운 뒤 SPRING_PROFILES_ACTIVE=social 로 실행하면 활성화된다.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ObjectProvider<ClientRegistrationRepository> clients)
            throws Exception {
        if (clients.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .userInfoEndpoint(u -> u.userService(oAuth2UserService))
                    .successHandler(oAuth2SuccessHandler));
        }
        http
            .csrf(csrf -> csrf.disable())          // JWT 라 세션 쿠키를 쓰지 않는다
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // /error 를 막으면 400·500 이 전부 403 으로 바뀌어 원인을 알 수 없게 된다
                .requestMatchers("/error").permitAll()
                .requestMatchers("/api/auth/**", "/oauth2/**", "/login/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/reference/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            // **인증 실패는 401, 권한 부족은 403.** 안 걸면 Spring 기본값
            // Http403ForbiddenEntryPoint 가 **둘 다 403** 으로 내보낸다. 화면은 401 만
            // 세션 만료로 보도록 일부러 그렇게 돼 있어서(api/client.js), 만료된 토큰이
            // "세션 끊김"이 아니라 "알 수 없는 오류"가 되고 시나리오 목록이
            // **"저장된 시나리오가 없습니다"** 라는 거짓말로 뜬다
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private CorsConfigurationSource corsSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173"));   // React 개발 서버
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
