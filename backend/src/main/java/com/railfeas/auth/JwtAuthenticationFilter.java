package com.railfeas.auth;

import com.railfeas.user.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 토큰에서는 **누구인지(사용자 번호)만** 읽고, 권한은 **매 요청 DB 에서** 읽는다.
 *
 * 전에는 로그인할 때 토큰에 적은 `role` 을 믿었다. 그래서 관리자를 일반 사용자로 내려도 이미 받은
 * 토큰이 만료될 때까지(720분) 관리자 API 가 열려 있었고, 계정을 지워도 그 토큰은 로그인된 것으로
 * 통했다(11-04 서버 권한 점검). 기본 키 한 줄 조회라 요청마다 해도 싸다
 * (`JwtAuthenticationFilterTest`).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider, UserRepository users) {
        this.tokenProvider = tokenProvider;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            Claims claims = tokenProvider.parse(header.substring(PREFIX.length()));
            if (claims != null) {
                // 지워진 계정이면 아무것도 걸지 않는다 — 익명 요청이 되어 보호된 API 는 401 이다
                users.findById(tokenProvider.userId(claims)).ifPresent(user -> {
                    var authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());
                    var authentication = new UsernamePasswordAuthenticationToken(
                            user.getId(), null, List.of(authority));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }
        }
        chain.doFilter(request, response);
    }
}
