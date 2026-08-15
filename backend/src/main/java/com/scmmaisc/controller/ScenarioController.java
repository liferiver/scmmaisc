package com.scmmaisc.controller;

import com.scmmaisc.common.ApiResult;
import com.scmmaisc.service.ScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 场景接口（C2 GET /api/scenarios?chapterId=、C3 GET /api/scenarios/{moduleId}）。
 */
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
public class ScenarioController {

    private final ScenarioService scenarioService;

    @GetMapping
    public ApiResult<List<ScenarioService.ScenarioSummaryVO>> list(
            @RequestParam(required = false) Long chapterId) {
        return ApiResult.ok(scenarioService.listByChapter(chapterId));
    }

    @GetMapping("/{moduleId}")
    public ApiResult<ScenarioService.ScenarioDetailVO> detail(@PathVariable String moduleId) {
        return ApiResult.ok(scenarioService.getByModuleId(moduleId));
    }
}
