package com.railfeas.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * 소셜 로그인 공급자 등록 — **`.env` 에 키가 있는 공급자만** 켠다.
 *
 * <p>전에는 `social` 프로파일(`application-social.yml`)이 Google·카카오를 **함께** 등록해서, 카카오 키만
 * 넣으면 Google 의 빈 client-id 때문에 서버가 뜨지 않았다. 이제 프로파일 없이 키만 넣으면 된다.
 * 하나도 없으면 저장소 빈을 만들지 않고 `SecurityConfig` 가 소셜 로그인을 걸지 않는다(버튼도 안 보인다).
 *
 * <ul>
 *   <li><b>카카오</b> — client-id 는 앱의 REST API 키다. 시크릿은 콘솔에서 켰을 때만 넣는다 — 없으면 보내지
 *       않는다(`NONE`). 범위는 닉네임뿐이다: 이메일(`account_email`)은 개인 개발 앱이 동의 항목으로 받기
 *       어려워 요청하면 카카오가 KOE205 로 막는다</li>
 *   <li><b>Google</b> — ID 와 시크릿이 둘 다 있어야 한다</li>
 * </ul>
 * 공급자 콘솔에 등록할 리다이렉트 주소는 `http://localhost:9090/login/oauth2/code/{google|kakao}` 다.
 */
@Configuration
public class OAuth2ClientConfig {

    private static final String REDIRECT = "{baseUrl}/login/oauth2/code/{registrationId}";

    @Bean
    @Conditional(AnySocialKey.class)
    public ClientRegistrationRepository clientRegistrationRepository(Environment env) {
        return new InMemoryClientRegistrationRepository(registrations(env));
    }

    static List<ClientRegistration> registrations(Environment env) {
        return registrations(env.getProperty("GOOGLE_CLIENT_ID"), env.getProperty("GOOGLE_CLIENT_SECRET"),
                env.getProperty("KAKAO_CLIENT_ID"), env.getProperty("KAKAO_CLIENT_SECRET"));
    }

    static List<ClientRegistration> registrations(String googleId, String googleSecret,
                                                  String kakaoId, String kakaoSecret) {
        List<ClientRegistration> out = new ArrayList<>();
        if (present(googleId) && present(googleSecret)) {
            out.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(googleId.trim()).clientSecret(googleSecret.trim())
                    .scope("profile", "email")
                    .build());
        }
        if (present(kakaoId)) {
            boolean secret = present(kakaoSecret);
            out.add(ClientRegistration.withRegistrationId("kakao")
                    .clientId(kakaoId.trim())
                    .clientSecret(secret ? kakaoSecret.trim() : null)
                    .clientAuthenticationMethod(secret
                            ? ClientAuthenticationMethod.CLIENT_SECRET_POST : ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri(REDIRECT)
                    .scope("profile_nickname")
                    .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                    .tokenUri("https://kauth.kakao.com/oauth/token")
                    .userInfoUri("https://kapi.kakao.com/v2/user/me")
                    .userNameAttributeName("id")
                    .clientName("Kakao")
                    .build());
        }
        return out;
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    /** 키가 하나라도 있을 때만 저장소를 만든다 — 빈 목록으로 만들면 기동이 막힌다 */
    static class AnySocialKey implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !registrations(context.getEnvironment()).isEmpty();
        }
    }
}
