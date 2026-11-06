package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.railfeas.common.ApiException;
import com.railfeas.user.AuthProvider;
import com.railfeas.user.Role;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * 소셜 로그인(제안서 기능 1)의 **화면과 이어지는 부분**.
 *
 * <p>백엔드는 있었지만 화면이 없었다 — 버튼도, 돌아온 토큰을 받는 `/oauth/callback` 도, 토큰만 들고
 * 돌아온 화면이 닉네임·권한을 알아낼 `/me` 도 없었다(11-04). 토큰은 `?token=` 으로 보내 방문
 * 기록·Referer 에 남을 수 있었다 — `#` 뒤는 서버로 가지 않는다.
 */
class SocialLoginTest {

    private static final String FRONT = "http://localhost:5173/oauth/callback";
    private final JwtTokenProvider tokens =
            new JwtTokenProvider(new JwtProperties("test-secret-that-is-at-least-32-bytes!!", 60));

    private static User user(long id) {
        User u = User.builder().email("s@example.test").nickname("소셜").provider(AuthProvider.GOOGLE)
                .providerUid("g-1").role(Role.USER).build();
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return u;
    }

    private static TestingAuthenticationToken oauth(long userId) {
        var principal = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("userId", userId, "sub", "g-1"), "sub");
        return new TestingAuthenticationToken(principal, null);
    }

    @Test
    @DisplayName("성공하면 토큰을 # 뒤에 실어 보낸다 — ? 로 보내면 방문 기록·Referer 에 남는다")
    void tokenGoesInFragment() throws Exception {
        UserRepository users = Mockito.mock(UserRepository.class);
        Mockito.when(users.findById(3L)).thenReturn(Optional.of(user(3)));
        var res = new MockHttpServletResponse();

        new OAuth2SuccessHandler(tokens, users, FRONT)
                .onAuthenticationSuccess(new MockHttpServletRequest(), res, oauth(3));

        assertThat(res.getRedirectedUrl()).startsWith(FRONT + "#token=").doesNotContain("?token=");
        String token = res.getRedirectedUrl().substring((FRONT + "#token=").length());
        assertThat(tokens.userId(tokens.parse(token))).isEqualTo(3L);
    }

    @Test
    @DisplayName("계정을 못 찾으면 500 이 아니라 #error= 로 돌려보낸다")
    void missingUserRedirectsWithError() throws Exception {
        UserRepository users = Mockito.mock(UserRepository.class);
        Mockito.when(users.findById(3L)).thenReturn(Optional.empty());
        var res = new MockHttpServletResponse();

        new OAuth2SuccessHandler(tokens, users, FRONT)
                .onAuthenticationSuccess(new MockHttpServletRequest(), res, oauth(3));

        assertThat(res.getRedirectedUrl()).startsWith(FRONT + "#error=").doesNotContain("token");
    }

    @SuppressWarnings("unchecked")
    private static AuthController controller(UserRepository users, ClientRegistrationRepository clients) {
        ObjectProvider<ClientRegistrationRepository> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(clients);
        return new AuthController(null, users, provider, null);
    }

    @Test
    @DisplayName("키가 없으면 소셜 목록이 비고, 등록돼 있으면 그 이름이다 — 화면이 버튼을 이것으로 고른다")
    void providers() {
        assertThat(controller(null, null).providers()).isEmpty();

        var google = ClientRegistration.withRegistrationId("google").clientId("x")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://a.example.test").tokenUri("https://t.example.test").build();
        assertThat(controller(null, new InMemoryClientRegistrationRepository(google)).providers())
                .isEqualTo(List.of("google"));
    }

    @Test
    @DisplayName("/me 는 토큰의 사람을 DB 에서 읽어 준다 — 토큰이 없으면 401")
    void me() {
        UserRepository users = Mockito.mock(UserRepository.class);
        Mockito.when(users.findById(3L)).thenReturn(Optional.of(user(3)));

        assertThatThrownBy(() -> controller(users, null).me(null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        var me = controller(users, null).me(3L);
        assertThat(me.userId()).isEqualTo(3L);
        assertThat(me.nickname()).isEqualTo("소셜");
        assertThat(me.role()).isEqualTo("USER");
    }

    // ── 이메일 겹침 ─────────────────────────────────────────────────────────────
    // 이메일로 가입한 사람이 같은 Gmail 로 Google 로그인을 누르면 새 계정 저장이 email UNIQUE 에 걸려
    // **500** 이었다 — 그 예외는 인증 실패가 아니라 실패 처리기를 안 타고 백엔드 오류 화면이 떴다(11-05).
    // 이메일로 자동 병합은 하지 않는다 — 공급자가 확인하지 않은 이메일이면 남의 계정에 들어가는 길이다

    @Test
    @DisplayName("이미 이은 소셜 계정은 그대로 쓴다 — 새로 저장하지 않는다")
    void existingSocialAccount() {
        UserRepository users = Mockito.mock(UserRepository.class);
        Mockito.when(users.findByProviderAndProviderUid(AuthProvider.GOOGLE, "g-1")).thenReturn(Optional.of(user(3)));

        assertThat(OAuth2UserService.upsert(users, AuthProvider.GOOGLE, "g-1", "s@example.test", "소셜").getId())
                .isEqualTo(3L);
        Mockito.verify(users, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("같은 이메일로 가입한 계정이 있으면 저장하지 않고 email_in_use 로 실패한다")
    void emailAlreadyUsed() {
        UserRepository users = Mockito.mock(UserRepository.class);
        Mockito.when(users.findByProviderAndProviderUid(AuthProvider.GOOGLE, "g-1")).thenReturn(Optional.empty());
        Mockito.when(users.existsByEmail("s@example.test")).thenReturn(true);

        assertThatThrownBy(() -> OAuth2UserService.upsert(users, AuthProvider.GOOGLE, "g-1", "s@example.test", "소셜"))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .satisfies(e -> assertThat(((OAuth2AuthenticationException) e).getError().getErrorCode())
                        .isEqualTo(OAuth2UserService.EMAIL_IN_USE));
        Mockito.verify(users, Mockito.never()).save(Mockito.any());
    }

    @Test
    @DisplayName("실패 처리기는 이메일 겹침의 안내만 그대로 전하고, 나머지는 일반 문구다")
    void failureMessage() throws Exception {
        var handler = new OAuth2FailureHandler(FRONT);
        var inUse = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), inUse, new OAuth2AuthenticationException(
                new OAuth2Error(OAuth2UserService.EMAIL_IN_USE, "이미 이 이메일로 가입한 계정이 있습니다", null)));
        assertThat(java.net.URLDecoder.decode(inUse.getRedirectedUrl(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(FRONT + "#error=이미 이 이메일로 가입한 계정이 있습니다");

        var other = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), other,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied", "내부 사정", null)));
        assertThat(java.net.URLDecoder.decode(other.getRedirectedUrl(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("소셜 로그인에 실패했습니다").doesNotContain("내부 사정");
    }

    // ── 칸 길이 ──────────────────────────────────────────────────────────────────
    // nickname VARCHAR(50) · email VARCHAR(150). 이메일 가입은 50자 검사를 거치지만 Google 이름은 아니다 —
    // 넘으면 위와 같은 500 이다(11-05)

    @Test
    @DisplayName("닉네임은 50자까지 — 이모지를 반으로 자르지 않는다")
    void nicknameFits() {
        assertThat(OAuth2UserService.fitNickname("가".repeat(60))).hasSize(50);
        String emoji = "😀".repeat(60);                           // 한 글자가 char 두 칸이다
        String fitted = OAuth2UserService.fitNickname(emoji);
        assertThat(fitted.codePointCount(0, fitted.length())).isEqualTo(50);
        assertThat(fitted).isEqualTo("😀".repeat(50));
        assertThat(OAuth2UserService.fitNickname("짧은 이름")).isEqualTo("짧은 이름");
    }

    @Test
    @DisplayName("150자를 넘는 이메일은 자르지 않고 공급자 번호로 대신한다")
    void tooLongEmailFallsBack() {
        String longEmail = "a".repeat(150) + "@gmail.com";
        assertThat(OAuth2UserService.fitEmail(longEmail, AuthProvider.GOOGLE, "123"))
                .isEqualTo("google_123@social.local");
        assertThat(OAuth2UserService.fitEmail(null, AuthProvider.KAKAO, "9")).isEqualTo("kakao_9@social.local");
        assertThat(OAuth2UserService.fitEmail("me@gmail.com", AuthProvider.GOOGLE, "1")).isEqualTo("me@gmail.com");
    }
}
