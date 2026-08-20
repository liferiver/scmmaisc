package com.scmmaisc.controller;

import com.scmmaisc.common.ApiResult;
import com.scmmaisc.service.discussion.DiscussionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 讨论接口（契约 D1-D8，风格对齐 RunController）：
 * D1 POST /api/discussions 202+排队位置；D2 GET /{id} 状态轮询；D3 GET /{id}/record 完整记录；
 * D4 POST /{id}/questions 学生插话 201；D5 POST /{id}/abandon 放弃；
 * D6 GET /history 历史列表；D7 GET /{id}/export Markdown 导出（US4）；D8 见场景接口（US5）。
 */
@RestController
@RequestMapping("/api/discussions")
@RequiredArgsConstructor
public class DiscussionController {

    private final DiscussionService discussionService;

    @PostConstruct
    void init() {
        // 服务重启后从 DB 恢复排队状态（FR-015 排队位置连续性）
        discussionService.rebuildPendingSessions();
    }

    /** D1：创建讨论（202 + sessionId + queuePosition）。 */
    @PostMapping
    public ResponseEntity<ApiResult<DiscussionService.CreateDiscussionResult>> create(
            @RequestBody DiscussionService.CreateDiscussionRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResult.ok(discussionService.create(request)));
    }

    /** D2：讨论状态与进度。 */
    @GetMapping("/{id}")
    public ApiResult<DiscussionService.DiscussionStatusVO> status(@PathVariable Long id,
                                                                  @RequestParam String clientId) {
        return ApiResult.ok(discussionService.status(id, clientId));
    }

    /** D3：完整讨论记录（回看 / 发言流，含运行中部分轮次）。 */
    @GetMapping("/{id}/record")
    public ApiResult<DiscussionService.DiscussionRecordVO> record(@PathVariable Long id,
                                                                  @RequestParam String clientId) {
        return ApiResult.ok(discussionService.record(id, clientId));
    }

    /** D4：提交学生插话（201 + questionId/roundNo/truncated；空白 400、终态 409）。 */
    @PostMapping("/{id}/questions")
    public ResponseEntity<ApiResult<DiscussionService.SubmitQuestionResult>> submitQuestion(
            @PathVariable Long id, @RequestParam String clientId,
            @RequestBody DiscussionService.SubmitQuestionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.ok(discussionService.submitQuestion(id, clientId, request)));
    }

    /** D6：历史讨论列表（clientId 过滤 + 可选 scenarioId + 时间倒序 + 分页默认 20 上限 50）。 */
    @GetMapping("/history")
    public ApiResult<DiscussionService.HistoryVO> history(@RequestParam String clientId,
                                                          @RequestParam(required = false) Long scenarioId,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return ApiResult.ok(discussionService.history(clientId, scenarioId, page, size));
    }

    /** D7：导出实验报告附录（Markdown；仅 COMPLETED 可导出，409；附件文件名 discussion-{moduleId}-{id}.md）。 */
    @GetMapping("/{id}/export")
    public ResponseEntity<String> export(@PathVariable Long id, @RequestParam String clientId) {
        DiscussionService.MarkdownExportVO vo = discussionService.exportMarkdown(id, clientId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + vo.filename() + "\"")
                .body(vo.content());
    }

    /** D5：放弃讨论（非终态可放弃，已生成发言保留）。 */
    @PostMapping("/{id}/abandon")
    public ApiResult<DiscussionService.AbandonResult> abandon(@PathVariable Long id,
                                                              @RequestParam String clientId) {
        return ApiResult.ok(discussionService.abandon(id, clientId));
    }
}
