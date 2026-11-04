package com.railfeas.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDto {

    public record SignupRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 64) String password,
            @NotBlank @Size(max = 50) String nickname) {
    }

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, Long userId, String nickname, String role) {
    }

    /** 소셜 로그인에서 토큰만 들고 돌아온 화면이 자기가 누구인지 알아낸다 (`/api/auth/me`) */
    public record MeResponse(Long userId, String nickname, String role) {
    }
}
