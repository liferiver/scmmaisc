package com.scmmaisc.service.discussion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.common.BizException;
import com.scmmaisc.common.ErrorCode;
import com.scmmaisc.entity.DiscussionConclusion;
import com.scmmaisc.entity.DiscussionQuestion;
import com.scmmaisc.entity.DiscussionSession;
import com.scmmaisc.entity.DiscussionUtterance;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.SimulationRun;
import com.scmmaisc.engine.OutputValue;
import com.scmmaisc.mapper.DiscussionConclusionMapper;
import com.scmmaisc.mapper.DiscussionQuestionMapper;
import com.scmmaisc.mapper.DiscussionSessionMapper;
import com.scmmaisc.mapper.DiscussionUtteranceMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import com.scmmaisc.mapper.SimulationRunMapper;
import com.scmmaisc.service.RunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 讨论服务（契约 D1-D8）：创建（校验 run 归属与 COMPLETED，快照固化于编排器执行期）、
 * 状态轮询、完整记录、放弃、历史与导出。有界并发（FR-015）：pendingSessions 内存集合
 * 记录排队/运行中会话，queuePosition = 集合大小 + 1（research.md §3）；终态移除。
 * D6 历史列表（clientId 过滤 + 可选 scenarioId + 时间倒序 + 分页）与 D7 Markdown 导出
 * （仅 COMPLETED，实验报告附录结构 SC-008）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscussionService {

    /** clientId 白名单（复用 RunService 同款规则）。 */
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{4,64}");

    /** 插话内容上限（schema discussion_question.content VARCHAR(200)，US3-AC3 超长截断）。 */
    private static final int MAX_QUESTION_LENGTH = 200;

    /** D6 历史分页：默认 20 条/页，上限 50（契约 D6）。 */
    private static final int HISTORY_PAGE_SIZE_DEFAULT = 20;
    private static final int HISTORY_PAGE_SIZE_MAX = 50;

    /** 导出角色名（对齐前端 UtteranceList 徽标）。 */
    private static final Map<String, String> ROLE_NAMES = Map.of(
            "LIU", "柳经理", "HUO", "霍教授", "JING", "景同学", "ZHONG", "钟同学");

    /** 导出时间格式（实验报告附录，SC-008）。 */
    private static final DateTimeFormatter EXPORT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DiscussionSessionMapper sessionMapper;
    private final DiscussionUtteranceMapper utteranceMapper;
    private final DiscussionQuestionMapper questionMapper;
    private final DiscussionConclusionMapper conclusionMapper;
    private final SimulationRunMapper runMapper;
    private final ScenarioMapper scenarioMapper;
    private final DiscussionOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    @Qualifier("discussionExecutor")
    private final ThreadPoolTaskExecutor discussionExecutor;

    /** 排队/运行中会话（QUEUED+RUNNING 非终态），用于排队位置计算与终态清理。 */
    private final ConcurrentHashMap<Long, Boolean> pendingSessions = new ConcurrentHashMap<>();

    /** D1：创建讨论（202 + sessionId + 排队位置）；run 须存在、归属、COMPLETED（409）。 */
    public CreateDiscussionResult create(CreateDiscussionRequest request) {
        if (request.runId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "runId 必填");
        }
        requireClientIdFormat(request.clientId());
        SimulationRun run = runMapper.selectById(request.runId());
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行不存在: " + request.runId());
        }
        if (!run.getClientId().equals(request.clientId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "运行记录不属于该客户端");
        }
        if (!RunService.STATUS_COMPLETED.equals(run.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "运行尚未完成，请先完成仿真运行");
        }

        int queuePosition;
        Long sessionId;
        synchronized (this) {
            queuePosition = pendingSessions.size() + 1;
            DiscussionSession session = new DiscussionSession();
            session.setRunId(run.getId());
            session.setScenarioId(run.getScenarioId());
            session.setClientId(request.clientId());
            session.setStatus(DiscussionOrchestrator.STATUS_QUEUED);
            session.setRoundNo(0);
            session.setQueuePosition(queuePosition);
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.insert(session);
            sessionId = session.getId();
            pendingSessions.put(sessionId, Boolean.TRUE);
        }
        // 异步执行：终态（含失败/完成）后移出排队集合，保证排队位置连续（FR-015）
        discussionExecutor.execute(() -> {
            try {
                orchestrator.start(sessionId);
            } finally {
                pendingSessions.remove(sessionId);
            }
        });
        return new CreateDiscussionResult(sessionId, DiscussionOrchestrator.STATUS_QUEUED, queuePosition);
    }

    /** D2：讨论状态与进度（2s 轮询）。 */
    public DiscussionStatusVO status(Long sessionId, String clientId) {
        DiscussionSession session = requireOwned(sessionId, clientId);
        Scenario scenario = scenarioMapper.selectById(session.getScenarioId());
        long utteranceCount = utteranceMapper.selectCount(
                new LambdaQueryWrapper<DiscussionUtterance>().eq(DiscussionUtterance::getSessionId, sessionId));
        long questionCount = questionMapper.selectCount(
                new LambdaQueryWrapper<DiscussionQuestion>().eq(DiscussionQuestion::getSessionId, sessionId));
        return new DiscussionStatusVO(session.getId(), session.getRunId(), session.getScenarioId(),
                scenario == null ? "" : scenario.getModuleId(),
                scenario == null ? "" : scenario.getName(),
                session.getStatus(), session.getRoundNo(), session.getQueuePosition(),
                (int) utteranceCount, (int) questionCount, !isTerminal(session.getStatus()));
    }

    /** D3：完整讨论记录（含运行中部分轮次回看；快照来自 run 表，单一事实源 SC-005）。 */
    public DiscussionRecordVO record(Long sessionId, String clientId) {
        DiscussionSession session = requireOwned(sessionId, clientId);
        SimulationRun run = runMapper.selectById(session.getRunId());
        Scenario scenario = scenarioMapper.selectById(session.getScenarioId());

        SnapshotVO snapshot = new SnapshotVO(parseParams(run), run.getSeed(), parseOutputs(run));
        List<RoundVO> rounds = new ArrayList<>();
        for (int r = 1; r <= 5; r++) {
            int round = r;
            List<UtteranceVO> utterances = utteranceMapper.selectList(
                            new LambdaQueryWrapper<DiscussionUtterance>()
                                    .eq(DiscussionUtterance::getSessionId, sessionId)
                                    .eq(DiscussionUtterance::getRoundNo, round)
                                    .orderByAsc(DiscussionUtterance::getId))
                    .stream()
                    .map(u -> new UtteranceVO(u.getId(), u.getAgentRole(), u.getContent(), u.getReplyQuestionId()))
                    .toList();
            if (!utterances.isEmpty()) {
                rounds.add(new RoundVO(round, PromptBuilder.ROUND_TITLES[round - 1], utterances));
            }
        }
        List<QuestionVO> questions = questionMapper.selectList(
                        new LambdaQueryWrapper<DiscussionQuestion>()
                                .eq(DiscussionQuestion::getSessionId, sessionId)
                                .orderByAsc(DiscussionQuestion::getId))
                .stream()
                .map(q -> new QuestionVO(q.getId(), q.getRoundNo(), q.getContent(), q.getResponded()))
                .toList();
        ConclusionVO conclusion = loadConclusion(sessionId);
        return new DiscussionRecordVO(session.getId(), session.getStatus(), session.getRoundNo(),
                session.getConclusionNote(), snapshot, rounds, questions, conclusion,
                scenario == null ? "" : scenario.getModuleId(),
                scenario == null ? "" : scenario.getName());
    }

    /** D5：放弃讨论（非终态可放弃；已生成发言保留，澄清 Q5）。 */
    public AbandonResult abandon(Long sessionId, String clientId) {
        DiscussionSession session = requireOwned(sessionId, clientId);
        if (isTerminal(session.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "讨论已处于终态: " + session.getStatus());
        }
        session.setStatus(DiscussionOrchestrator.STATUS_ABANDONED);
        session.setFinishedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        pendingSessions.remove(sessionId);
        return new AbandonResult(sessionId, DiscussionOrchestrator.STATUS_ABANDONED);
    }

    /** D4：提交学生插话（空白 400、超 200 字截断存储、终态 409；roundNo = 提交时轮次）。 */
    public SubmitQuestionResult submitQuestion(Long sessionId, String clientId, SubmitQuestionRequest request) {
        DiscussionSession session = requireOwned(sessionId, clientId);
        if (isTerminal(session.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "讨论已结束");
        }
        String content = request == null ? null : request.content();
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "问题不能为空");
        }
        boolean truncated = content.length() > MAX_QUESTION_LENGTH;
        DiscussionQuestion question = new DiscussionQuestion();
        question.setSessionId(sessionId);
        question.setRoundNo(session.getRoundNo());
        question.setContent(truncated ? content.substring(0, MAX_QUESTION_LENGTH) : content);
        question.setResponded(false);
        question.setCreatedAt(LocalDateTime.now());
        questionMapper.insert(question);
        return new SubmitQuestionResult(question.getId(), session.getRoundNo(), truncated);
    }

    /** D7：导出实验报告附录（Markdown；仅 COMPLETED 可导出，409）。 */
    public MarkdownExportVO exportMarkdown(Long sessionId, String clientId) {
        DiscussionSession session = requireOwned(sessionId, clientId);
        if (!DiscussionOrchestrator.STATUS_COMPLETED.equals(session.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "仅完成讨论可导出");
        }
        SimulationRun run = runMapper.selectById(session.getRunId());
        Scenario scenario = scenarioMapper.selectById(session.getScenarioId());
        String scenarioName = scenario == null ? "" : scenario.getName();
        String moduleId = scenario == null ? "" : scenario.getModuleId();

        StringBuilder md = new StringBuilder();
        // 标题 / 场景信息 / 导出时间
        md.append("# 多智能体研讨报告 —— ").append(scenarioName).append("\n\n");
        md.append("- 场景：").append(scenarioName).append("（").append(moduleId).append("）\n");
        md.append("- 会话ID：").append(sessionId).append("\n");
        md.append("- 导出时间：").append(LocalDateTime.now().format(EXPORT_TIME_FORMATTER)).append("\n\n");

        // 运行快照摘要（参数/种子/指标；复杂结构压缩，对齐前端 structuredSummary 逻辑）
        md.append("## 运行快照摘要\n\n");
        md.append("- 随机种子：").append(run.getSeed()).append("\n");
        md.append("- 参数：").append(summaryParams(parseParams(run))).append("\n");
        md.append("- 关键指标：").append(summaryOutputs(parseOutputs(run))).append("\n\n");

        // 五轮发言（含插话标注）
        md.append("## 五轮发言\n\n");
        Map<Long, String> questionTexts = questionMapper.selectList(
                        new LambdaQueryWrapper<DiscussionQuestion>()
                                .eq(DiscussionQuestion::getSessionId, sessionId))
                .stream().collect(Collectors.toMap(DiscussionQuestion::getId, DiscussionQuestion::getContent));
        for (int r = 1; r <= PromptBuilder.ROUND_TITLES.length; r++) {
            int round = r;
            List<DiscussionUtterance> utterances = utteranceMapper.selectList(
                            new LambdaQueryWrapper<DiscussionUtterance>()
                                    .eq(DiscussionUtterance::getSessionId, sessionId)
                                    .eq(DiscussionUtterance::getRoundNo, round)
                                    .orderByAsc(DiscussionUtterance::getId))
                    .stream().toList();
            if (utterances.isEmpty()) {
                continue;
            }
            md.append("### 第 ").append(round).append(" 轮 · ")
                    .append(PromptBuilder.ROUND_TITLES[round - 1]).append("\n\n");
            for (DiscussionUtterance u : utterances) {
                String roleName = ROLE_NAMES.getOrDefault(u.getAgentRole(), u.getAgentRole());
                md.append("- **").append(roleName).append("（").append(u.getAgentRole()).append("）**：");
                if (u.getReplyQuestionId() != null) {
                    String q = questionTexts.get(u.getReplyQuestionId());
                    md.append("（回应学生问题：").append(mdEscape(q == null ? "" : q)).append("）");
                }
                md.append(mdEscape(u.getContent())).append("\n");
            }
            md.append("\n");
        }

        // 学生插话清单
        List<DiscussionQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<DiscussionQuestion>()
                        .eq(DiscussionQuestion::getSessionId, sessionId)
                        .orderByAsc(DiscussionQuestion::getId));
        if (!questions.isEmpty()) {
            md.append("## 学生插话\n\n");
            for (DiscussionQuestion q : questions) {
                md.append("- 第 ").append(q.getRoundNo()).append(" 轮：").append(mdEscape(q.getContent()))
                        .append(q.getResponded() ? "（已回应）" : "（未回应）").append("\n");
            }
            md.append("\n");
        }

        // 三维结论卡片（12 要素全）+ 投票结果（FR-014）
        ConclusionVO conclusion = loadConclusion(sessionId);
        if (conclusion != null) {
            appendSection(md, "理论结论", conclusion.theory());
            appendSection(md, "实操结论", conclusion.practice());
            appendSection(md, "前沿结论", conclusion.frontier());
            String vote = conclusion.frontier().get("voteItem");
            if (vote != null && !vote.isBlank()) {
                md.append("## 兴趣投票\n\n").append(mdEscape(vote)).append("\n\n");
            }
        } else {
            md.append("## 三维结论\n\n（结论数据缺失）\n\n");
        }

        return new MarkdownExportVO("discussion-" + moduleId + "-" + sessionId + ".md", md.toString());
    }

    /** D6：历史讨论列表（clientId 过滤 + 可选 scenarioId + 创建时间倒序 + 分页）。 */
    public HistoryVO history(String clientId, Long scenarioId, Integer page, Integer size) {
        requireClientIdFormat(clientId);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? HISTORY_PAGE_SIZE_DEFAULT : Math.min(size, HISTORY_PAGE_SIZE_MAX);
        LambdaQueryWrapper<DiscussionSession> qw = new LambdaQueryWrapper<DiscussionSession>()
                .eq(DiscussionSession::getClientId, clientId)
                .orderByDesc(DiscussionSession::getCreatedAt)
                .orderByDesc(DiscussionSession::getId); // 同毫秒兜底，顺序稳定
        if (scenarioId != null) {
            qw.eq(DiscussionSession::getScenarioId, scenarioId);
        }
        // 计数查询不带 ORDER BY（H2 严格模式禁止聚合查询中引用非分组列）
        long total = sessionMapper.selectCount(new LambdaQueryWrapper<DiscussionSession>()
                .eq(DiscussionSession::getClientId, clientId)
                .eq(scenarioId != null, DiscussionSession::getScenarioId, scenarioId));
        List<DiscussionSession> sessions = sessionMapper.selectList(
                qw.last("LIMIT " + ((long) (p - 1) * s) + ", " + s));
        // 批量统计发言数，避免逐行 N+1
        List<Long> ids = sessions.stream().map(DiscussionSession::getId).toList();
        Map<Long, Long> utteranceCounts = ids.isEmpty() ? Map.of() : utteranceMapper.selectList(
                        new LambdaQueryWrapper<DiscussionUtterance>().in(DiscussionUtterance::getSessionId, ids))
                .stream().collect(Collectors.groupingBy(DiscussionUtterance::getSessionId, Collectors.counting()));
        List<HistoryItemVO> items = sessions.stream().map(session -> {
            Scenario scenario = scenarioMapper.selectById(session.getScenarioId());
            return new HistoryItemVO(session.getId(),
                    scenario == null ? "" : scenario.getModuleId(),
                    scenario == null ? "" : scenario.getName(),
                    session.getStatus(), session.getRoundNo(),
                    utteranceCounts.getOrDefault(session.getId(), 0L).intValue(),
                    session.getCreatedAt(), session.getFinishedAt());
        }).toList();
        return new HistoryVO(total, items);
    }

    // ---- 工具 ----

    private DiscussionSession requireOwned(Long sessionId, String clientId) {
        requireClientIdFormat(clientId);
        DiscussionSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "讨论不存在: " + sessionId);
        }
        if (!session.getClientId().equals(clientId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "讨论记录不属于该客户端");
        }
        return session;
    }

    private void requireClientIdFormat(String clientId) {
        if (clientId == null || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "clientId 格式不合法（4-64 位字母/数字/下划线/连字符）");
        }
    }

    private static boolean isTerminal(String status) {
        return DiscussionOrchestrator.STATUS_COMPLETED.equals(status)
                || DiscussionOrchestrator.STATUS_FAILED.equals(status)
                || DiscussionOrchestrator.STATUS_ABANDONED.equals(status);
    }

    private Map<String, Object> parseParams(SimulationRun run) {
        try {
            return objectMapper.readValue(run.getParams(), new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("运行参数解析失败", e);
        }
    }

    private List<OutputValue> parseOutputs(SimulationRun run) {
        if (run.getResult() == null || run.getResult().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(run.getResult(), new TypeReference<List<OutputValue>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("运行结果解析失败", e);
        }
    }

    private ConclusionVO loadConclusion(Long sessionId) {
        DiscussionConclusion c = conclusionMapper.selectOne(
                new LambdaQueryWrapper<DiscussionConclusion>().eq(DiscussionConclusion::getSessionId, sessionId));
        if (c == null) {
            return null;
        }
        return new ConclusionVO(parseSection(c.getTheoryJson()), parseSection(c.getPracticeJson()),
                parseSection(c.getFrontierJson()));
    }

    private Map<String, String> parseSection(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("结论解析失败", e);
        }
    }

    /** 导出参数摘要：k=v; k=v（对齐 PromptBuilder 摘要风格）。 */
    private String summaryParams(Map<String, Object> params) {
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                .collect(Collectors.joining("; "));
    }

    /** 导出指标摘要：标量展示值+单位，复杂结构压缩描述（对齐前端 structuredSummary）。 */
    private String summaryOutputs(List<OutputValue> outputs) {
        return outputs.stream().map(o -> {
            if (o.type() == null) {
                return o.label() + "=" + o.value();
            }
            return switch (o.type()) {
                case "series" -> o.label() + "=曲线图";
                case "topo" -> o.label() + "=拓扑图";
                case "heatmap" -> o.label() + "=热力图";
                case "compare", "dist", "gauge" -> o.label() + "=对比数据";
                default -> o.label() + "=" + o.value() + (o.unit() == null ? "" : o.unit());
            };
        }).collect(Collectors.joining("; "));
    }

    /** 结论小节：标题 + 要素键值列表。 */
    private void appendSection(StringBuilder md, String title, Map<String, String> items) {
        md.append("### ").append(title).append("\n\n");
        for (Map.Entry<String, String> e : items.entrySet()) {
            md.append("- **").append(e.getKey()).append("**：")
                    .append(mdEscape(e.getValue())).append("\n");
        }
        md.append("\n");
    }

    /** Markdown 行内转义（防标题/列表/加粗结构注入；换行转 <br>）。 */
    private static String mdEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("\n", "<br>");
    }

    /** 排队位置重建（服务重启后内存集合从 DB 恢复，保证位置连续）。 */
    public void rebuildPendingSessions() {
        List<DiscussionSession> active = sessionMapper.selectList(new LambdaQueryWrapper<DiscussionSession>()
                .in(DiscussionSession::getStatus,
                        DiscussionOrchestrator.STATUS_QUEUED, DiscussionOrchestrator.STATUS_RUNNING));
        pendingSessions.clear();
        active.forEach(s -> pendingSessions.put(s.getId(), Boolean.TRUE));
        log.info("讨论排队状态重建完成：活跃会话 {} 个", pendingSessions.size());
    }

    // ---- 契约模型（D1-D8） ----

    /** D1 请求体。 */
    public record CreateDiscussionRequest(Long runId, String clientId) {
    }

    /** D1 响应体：{ sessionId, status, queuePosition }。 */
    public record CreateDiscussionResult(Long sessionId, String status, Integer queuePosition) {
    }

    /** D2 响应体：状态与进度。 */
    public record DiscussionStatusVO(Long sessionId, Long runId, Long scenarioId, String moduleId,
                                     String scenarioName, String status, Integer roundNo,
                                     Integer queuePosition, Integer utteranceCount,
                                     Integer questionCount, Boolean abandonable) {
    }

    /** D3 响应体：完整记录。 */
    public record DiscussionRecordVO(Long sessionId, String status, Integer roundNo,
                                     String conclusionNote, SnapshotVO snapshot,
                                     List<RoundVO> rounds, List<QuestionVO> questions,
                                     ConclusionVO conclusion, String moduleId, String scenarioName) {
    }

    /** 运行快照（SC-005 可回溯依据）。 */
    public record SnapshotVO(Map<String, Object> params, Long seed, List<OutputValue> outputs) {
    }

    public record RoundVO(Integer roundNo, String title, List<UtteranceVO> utterances) {
    }

    public record UtteranceVO(Long id, String agentRole, String content, Long replyQuestionId) {
    }

    public record QuestionVO(Long id, Integer roundNo, String content, Boolean responded) {
    }

    /** 三维结论（camelCase 键与 ConclusionParser 一致）。 */
    public record ConclusionVO(Map<String, String> theory, Map<String, String> practice,
                               Map<String, String> frontier) {
    }

    /** D5 响应体。 */
    public record AbandonResult(Long sessionId, String status) {
    }

    /** D4 请求体：{ content }。 */
    public record SubmitQuestionRequest(String content) {
    }

    /** D4 响应体：{ questionId, roundNo, truncated }（201）。 */
    public record SubmitQuestionResult(Long questionId, Integer roundNo, Boolean truncated) {
    }

    /** D6 响应体：{ total, items }（历史分页列表）。 */
    public record HistoryVO(long total, List<HistoryItemVO> items) {
    }

    /** D6 历史条目（列表展示字段）。 */
    public record HistoryItemVO(Long sessionId, String moduleId, String scenarioName, String status,
                                Integer roundNo, Integer utteranceCount, LocalDateTime createdAt,
                                LocalDateTime finishedAt) {
    }

    /** D7 导出结果：附件文件名（discussion-{moduleId}-{sessionId}.md）+ Markdown 正文。 */
    public record MarkdownExportVO(String filename, String content) {
    }
}
