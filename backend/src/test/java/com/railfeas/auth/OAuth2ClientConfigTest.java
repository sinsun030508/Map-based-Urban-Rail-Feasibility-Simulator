package com.railfeas.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * **키가 있는 공급자만 켠다.** 전에는 `social` 프로파일이 Google·카카오를 함께 등록해, 카카오 키만
 * 넣으면 Google 의 빈 client-id 때문에 서버가 뜨지 않았다(11-05, 카카오 REST API 키를 받았을 때).
 */
class OAuth2ClientConfigTest {

    @Test
    @DisplayName("키가 하나도 없으면 아무것도 등록하지 않는다 — 소셜 로그인이 꺼진다")
    void none() {
        assertThat(OAuth2ClientConfig.registrations(null, null, null, null)).isEmpty();
        assertThat(OAuth2ClientConfig.registrations(" ", "", "", " ")).isEmpty();
    }

    @Test
    @DisplayName("카카오 키만 있으면 카카오만 켠다 — 시크릿이 없으면 보내지 않는다")
    void kakaoOnly() {
        var regs = OAuth2ClientConfig.registrations(null, null, "rest-api-key", null);

        assertThat(regs).extracting(ClientRegistration::getRegistrationId).containsExactly("kakao");
        ClientRegistration kakao = regs.get(0);
        assertThat(kakao.getClientId()).isEqualTo("rest-api-key");
        assertThat(kakao.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.NONE);
        // 이메일은 개인 개발 앱이 동의 항목으로 받기 어렵다 — 요청하면 카카오가 KOE205 로 로그인을 막는다.
        // 이메일이 없으면 OAuth2UserService 가 kakao_{id}@social.local 로 채운다
        assertThat(kakao.getScopes()).containsExactly("profile_nickname");
        assertThat(kakao.getRedirectUri()).isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
    }

    @Test
    @DisplayName("카카오 시크릿을 켜 두었으면 본문에 실어 보낸다")
    void kakaoWithSecret() {
        var kakao = OAuth2ClientConfig.registrations(null, null, "rest-api-key", "secret").get(0);
        assertThat(kakao.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(kakao.getClientSecret()).isEqualTo("secret");
    }

    @Test
    @DisplayName("Google 은 ID 와 시크릿이 둘 다 있어야 켠다")
    void googleNeedsBoth() {
        assertThat(OAuth2ClientConfig.registrations("gid", null, null, null)).isEmpty();
        assertThat(OAuth2ClientConfig.registrations("gid", "gsecret", "k", null))
                .extracting(ClientRegistration::getRegistrationId).containsExactly("google", "kakao");
    }
}
