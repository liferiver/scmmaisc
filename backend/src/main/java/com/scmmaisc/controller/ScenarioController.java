package com.scmmaisc.controller;

import com.scmmaisc.common.ApiResult;
import com.scmmaisc.common.BizException;
import com.scmmaisc.common.ErrorCode;
import com.scmmaisc.service.ScenarioService;
import com.scmmaisc.service.discussion.ScenarioDiscussionProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 场景接口（C2 GET /api/scenarios?chapterId=、C3 GET /api/scenarios/{moduleId}、
 * D8 GET /api/scenarios/{moduleId}/discussion-profile 场景讨论配置，US5）。
 */
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
public class ScenarioController {

    private final ScenarioService scenarioService;
    private final ScenarioDiscussionProfileService profileService;

    @GetMapping
    public ApiResult<List<ScenarioService.ScenarioSummaryVO>> list(
            @RequestParam(required = false) Long chapterId) {
        return ApiResult.ok(scenarioService.listByChapter(chapterId));
    }

    @GetMapping("/{moduleId}")
    public ApiResult<ScenarioService.ScenarioDetailVO> detail(@PathVariable String moduleId) {
        return ApiResult.ok(scenarioService.getByModuleId(moduleId));
    }

    /** D8：场景讨论配置（84 场景全覆盖，SC-001；未找到 → 404）。 */
    @GetMapping("/{moduleId}/discussion-profile")
    public ApiResult<ScenarioDiscussionProfileService.ProfileVO> discussionProfile(
            @PathVariable String moduleId) {
        ScenarioDiscussionProfileService.ProfileVO vo = profileService.getByModuleId(moduleId);
        if (vo == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "场景讨论配置不存在: " + moduleId);
        }
        return ApiResult.ok(vo);
    }
}
