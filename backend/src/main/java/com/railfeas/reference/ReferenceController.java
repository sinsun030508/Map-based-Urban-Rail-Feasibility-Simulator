package com.railfeas.reference;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트엔드가 수단 목록·규제 구역을 그리려면 로그인 전에도 읽어야 해서 공개한다. */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final ModeCapacityRepository modes;
    private final RestrictedZoneRepository zones;

    public ReferenceController(ModeCapacityRepository modes, RestrictedZoneRepository zones) {
        this.modes = modes;
        this.zones = zones;
    }

    @GetMapping("/modes")
    public List<ModeCapacity> modes() {
        return modes.findAll();
    }

    @GetMapping("/zones")
    public List<RestrictedZone> zones() {
        return zones.findAll();
    }
}
