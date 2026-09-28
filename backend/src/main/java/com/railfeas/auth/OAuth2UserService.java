package com.railfeas.auth;

import java.util.Map;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.railfeas.user.AuthProvider;
import com.railfeas.user.Role;
import com.railfeas.user.User;
import com.railfeas.user.UserRepository;

/**
 * 구글과 카카오가 주는 속성 이름이 다르다.
 *   구글   : sub / email / name
 *   카카오 : id / kakao_account.email / kakao_account.profile.nickname
 */
@Service
public class OAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository users;

    public OAuth2UserService(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(request);
        AuthProvider provider = AuthProvider.valueOf(
                request.getClientRegistration().getRegistrationId().toUpperCase());
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String uid;
        String email;
        String nickname;
        if (provider == AuthProvider.KAKAO) {
            uid = String.valueOf(attributes.get("id"));
            Map<String, Object> account = castMap(attributes.get("kakao_account"));
            email = account == null ? null : (String) account.get("email");
            Map<String, Object> profile = account == null ? null : castMap(account.get("profile"));
            nickname = profile == null ? null : (String) profile.get("nickname");
        } else {
            uid = (String) attributes.get("sub");
            email = (String) attributes.get("email");
            nickname = (String) attributes.get("name");
        }
        // 카카오는 이메일 제공 동의가 선택이라 비어 올 수 있다
        if (email == null) {
            email = provider.name().toLowerCase() + "_" + uid + "@social.local";
        }
        if (nickname == null) {
            nickname = "사용자" + uid;
        }

        User user = upsert(provider, uid, email, nickname);
        return new DefaultOAuth2User(oAuth2User.getAuthorities(),
                Map.of("userId", user.getId(),
                        "email", user.getEmail(),
                        "nickname", user.getNickname()),
                "userId");
    }

    private User upsert(AuthProvider provider, String uid, String email, String nickname) {
        return users.findByProviderAndProviderUid(provider, uid)
                .orElseGet(() -> users.save(User.builder()
                        .email(email)
                        .nickname(nickname)
                        .provider(provider)
                        .providerUid(uid)
                        .role(Role.USER)
                        .build()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
