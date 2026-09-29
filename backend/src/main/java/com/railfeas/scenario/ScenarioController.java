package com.railfeas.scenario;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScenarioDto.DetailResponse create(@AuthenticationPrincipal Long userId,
                                             @Valid @RequestBody ScenarioDto.SaveRequest request) {
        return scenarioService.create(userId, request);
    }

    @GetMapping
    public List<ScenarioDto.SummaryResponse> list(@AuthenticationPrincipal Long userId) {
        return scenarioService.list(userId);
    }

    @GetMapping("/{id}")
    public ScenarioDto.DetailResponse get(@AuthenticationPrincipal Long userId,
                                          @PathVariable Long id) {
        return scenarioService.get(userId, id);
    }

    @PutMapping("/{id}")
    public ScenarioDto.DetailResponse update(@AuthenticationPrincipal Long userId,
                                             @PathVariable Long id,
                                             @Valid @RequestBody ScenarioDto.SaveRequest request) {
        return scenarioService.update(userId, id, request);
    }

    @PostMapping("/{id}/calculate")
    public ScenarioDto.DetailResponse calculate(@AuthenticationPrincipal Long userId,
                                                @PathVariable Long id) {
        return scenarioService.calculate(userId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        scenarioService.delete(userId, id);
    }
}
