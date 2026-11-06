package com.railfeas.common;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * API 문서(`/swagger-ui.html`, 제안서 기능 외 산출물). `SWAGGER_ENABLED=true` 일 때만 열린다 — 관리자 경로까지
 * 드러내므로 기본은 닫는다. 화면의 Authorize 에 로그인 응답의 `accessToken` 을 넣으면 보호된 API 를 시험할 수 있다.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "RailFeas API", version = "0.1.0",
                description = "노선 타당성 분석 시뮬레이터 — 건설비·이용객·B/C 산출과 선형 제안"),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {
}
