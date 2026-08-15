package com.scmmaisc.controller;

import com.scmmaisc.common.ApiResult;
import com.scmmaisc.service.RunService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行接口（T023，契约 C4–C7）：
 * POST /api/runs 202+runId；GET /api/runs/{id}?clientId= 状态轮询；
 * GET /api/runs/{id}/result?clientId= 结果；DELETE /api/runs/{id}?clientId= 取消。
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RunController {

    private final RunService runService;

    @PostMapping
    public ResponseEntity<ApiResult<RunService.CreateRunResult>> create(
            @RequestBody RunService.ScenarioRunRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResult.ok(runService.create(request)));
    }

    @GetMapping("/{id}")
    public ApiResult<RunService.RunStatusVO> status(@PathVariable Long id,
                                                    @RequestParam String clientId) {
        return ApiResult.ok(runService.status(id, clientId));
    }

    @GetMapping("/{id}/result")
    public ApiResult<RunService.RunResultVO> result(@PathVariable Long id,
                                                    @RequestParam String clientId) {
        return ApiResult.ok(runService.result(id, clientId));
    }

    @DeleteMapping("/{id}")
    public ApiResult<RunService.CancelResult> cancel(@PathVariable Long id,
                                                     @RequestParam String clientId) {
        return ApiResult.ok(runService.cancel(id, clientId));
    }
}
