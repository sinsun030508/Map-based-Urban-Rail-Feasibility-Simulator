package com.railfeas.transit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 키는 헤더로만 보낸다 — 주소에 넣으면 접속 기록에 남는다. 8초 안에 답이 없으면 포기한다. */
@Component
public class HttpTmapClient implements TmapClient {

    private static final URI ENDPOINT = URI.create("https://apis.openapi.sk.com/transit/routes");
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final DateTimeFormatter DTTM = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper mapper;
    private final String appKey;

    public HttpTmapClient(ObjectMapper mapper, @Value("${app.tmap.app-key:}") String appKey) {
        this.mapper = mapper;
        this.appKey = appKey.trim();
    }

    @Override
    public Reply search(BigDecimal fromLat, BigDecimal fromLng, BigDecimal toLat, BigDecimal toLng, LocalDateTime at)
            throws IOException, InterruptedException {
        String body = mapper.writeValueAsString(Map.of(
                "startX", fromLng.toPlainString(), "startY", fromLat.toPlainString(),
                "endX", toLng.toPlainString(), "endY", toLat.toPlainString(),
                "count", TmapParser.MAX, "lang", 0, "format", "json", "searchDttm", DTTM.format(at)));
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(TIMEOUT)
                .header("appKey", appKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), response.body());
    }
}
