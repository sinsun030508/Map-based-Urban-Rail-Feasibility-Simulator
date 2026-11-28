package com.railfeas.transit;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 사용자만 부른다(`SecurityConfig` 의 기본 규칙). GET 은 버튼을 보일지 묻는 것이라 TMAP 을 부르지 않는다. */
@io.swagger.v3.oas.annotations.tags.Tag(name = "대중교통 비교")
@RestController
@RequestMapping("/api/route/transit")
public class TransitController {

    private final TransitService transit;

    public TransitController(TransitService transit) {
        this.transit = transit;
    }

    @GetMapping
    public TransitDto.Availability availability() {
        return new TransitDto.Availability(transit.enabled());
    }

    @PostMapping
    public TransitDto.Response search(@Valid @RequestBody TransitDto.Request request) {
        return transit.search(request.from(), request.to());
    }
}
