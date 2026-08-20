package com.scmmaisc.service.discussion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.entity.DiscussionConclusion;
import com.scmmaisc.entity.DiscussionQuestion;
import com.scmmaisc.entity.DiscussionSession;
import com.scmmaisc.entity.DiscussionUtterance;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.SimulationRun;
import com.scmmaisc.engine.OutputValue;
import com.scmmaisc.llm.LlmClient;
import com.scmmaisc.llm.LlmException;
import com.scmmaisc.llm.LlmMessage;
import com.scmmaisc.mapper.DiscussionConclusionMapper;
import com.scmmaisc.mapper.DiscussionQuestionMapper;
import com.scmmaisc.mapper.DiscussionSessionMapper;
import com.scmmaisc.mapper.DiscussionUtteranceMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import com.scmmaisc.mapper.SimulationRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 五轮讨论编排器（research.md §3/§4）：单会话串行执行 R1-R5（每轮按固定角色顺序
 * 逐条调用 LLM 并逐条落库，保证增量呈现与回应前文的确定性），随后生成三维结论；
 * 单轮 LLM 失败重试 1 次，仍失败 → 会话 FAILED（已生成内容保留）；结论解析走
 * ConclusionParser 容错链（重试 1 次 → 降级占位，状态仍 COMPLETED）。
 * {@link #run(Long)} 为同步执行入口（测试直接调用），异步由 DiscussionService 提交线程池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscussionOrchestrator {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    /** 发言内容截断上限（宪法 IV 内存有界）。 */
    private static final int MAX_UTTERANCE_LENGTH = 4000;

    private static final Map<String, String> PERSONA_FILES = Map.of(
            "LIU", "prompts/personas/liu.md",
            "HUO", "prompts/personas/huo.md",
            "JING", "prompts/personas/jing.md",
            "ZHONG", "prompts/personas/zhong.md");

    private final DiscussionSessionMapper sessionMapper;
    private final DiscussionUtteranceMapper utteranceMapper;
    private final DiscussionQuestionMapper questionMapper;
    private final DiscussionConclusionMapper conclusionMapper;
    private final SimulationRunMapper runMapper;
    private final ScenarioMapper scenarioMapper;
    private final LlmClient llmClient;
    private final PromptBuilder promptBuilder;
    private final ConclusionParser conclusionParser;
    private final ScenarioDiscussionProfileService profileService;
    private final ObjectMapper objectMapper;

    /** 异步入口（由 DiscussionService 在线程池中调用）。 */
    public void start(Long sessionId) {
        run(sessionId);
    }

    /** 同步执行完整五轮 + 结论（测试直接调用；重复调用幂等：已终态直接返回）。 */
    public void run(Long sessionId) {
        DiscussionSession session = sessionMapper.selectById(sessionId);
        if (session == null || isTerminal(session.getStatus())) {
            return;
        }
        session.setStatus(STATUS_RUNNING);
        session.setRoundNo(0);
        session.setStartedAt(LocalDateTime.now());
        session.setQueuePosition(null);
        sessionMapper.updateById(session);
        if (isAbandoned(sessionId)) {
            return; // 创建后立即放弃的竞态兜底：不再执行任何轮次
        }
        try {
            executeRounds(session);
            if (isAbandoned(session.getId())) {
                log.info("讨论已被放弃，停止后续执行: sessionId={}", sessionId);
                return; // D5：放弃后不再生成发言与结论
            }
            generateConclusion(session);
            session.setStatus(STATUS_COMPLETED);
            sessionMapper.updateById(session);
        } catch (LlmException e) {
            // 单轮重试后仍失败的兜底：已生成发言保留（FR-011），原因仅记录服务端日志
            log.warn("讨论执行失败: sessionId={}, round={}", sessionId, session.getRoundNo(), e);
            if (!isAbandoned(session.getId())) {
                session.setStatus(STATUS_FAILED);
                sessionMapper.updateById(session);
            }
        } finally {
            // 重新读取会话，避免把 ABANDONED/FAILED 等终态覆盖回 RUNNING
            DiscussionSession fresh = sessionMapper.selectById(sessionId);
            if (fresh != null) {
                fresh.setFinishedAt(LocalDateTime.now());
                sessionMapper.updateById(fresh);
            }
        }
    }

    private void executeRounds(DiscussionSession session) {
        SimulationRun run = runMapper.selectById(session.getRunId());
        Scenario scenario = scenarioMapper.selectById(session.getScenarioId());
        PromptBuilder.Context ctx = buildContext(session, run, scenario);

        List<PromptBuilder.UtteranceView> history = new ArrayList<>();
        for (int round = 1; round <= PromptBuilder.ROUND_TITLES.length; round++) {
            if (isAbandoned(session.getId())) {
                return;
            }
            session.setRoundNo(round);
            sessionMapper.updateById(session);
            String pendingQuestion = loadPendingQuestion(session.getId());
            boolean questionInjected = pendingQuestion != null;
            for (String role : PromptBuilder.ROUND_ORDER) {
                if (isAbandoned(session.getId())) {
                    return;
                }
                List<LlmMessage> messages = promptBuilder.build(
                        PERSONA_FILES.get(role), ctx, round, role, history, pendingQuestion);
                String text = callWithRetry(messages);
                if (isAbandoned(session.getId())) {
                    return; // 调用期间被放弃：不落库
                }
                insertUtterance(session.getId(), round, role, text,
                        questionInjected ? pendingQuestionId(session.getId()) : null);
                history.add(new PromptBuilder.UtteranceView(role, text));
            }
            if (questionInjected) {
                markQuestionResponded(session.getId());
            }
        }
    }

    private void generateConclusion(DiscussionSession session) {
        SimulationRun run = runMapper.selectById(session.getRunId());
        Scenario scenario = scenarioMapper.selectById(session.getScenarioId());
        PromptBuilder.Context ctx = buildContext(session, run, scenario);

        List<PromptBuilder.UtteranceView> history = utteranceMapper.selectList(
                        new LambdaQueryWrapper<DiscussionUtterance>()
                                .eq(DiscussionUtterance::getSessionId, session.getId())
                                .orderByAsc(DiscussionUtterance::getId))
                .stream().map(u -> new PromptBuilder.UtteranceView(u.getAgentRole(), u.getContent())).toList();

        session.setRoundNo(6); // 结论生成中（D2 契约：6=结论生成中）
        sessionMapper.updateById(session);

        if (isAbandoned(session.getId())) {
            return;
        }
        List<LlmMessage> messages = promptBuilder.buildConclusion(PERSONA_FILES.get("HUO"), ctx, history);
        ConclusionParser.ParseResult result = conclusionParser.parse(callWithRetry(messages), () -> callWithRetry(messages));

        DiscussionConclusion conclusion = new DiscussionConclusion();
        conclusion.setSessionId(session.getId());
        conclusion.setTheoryJson(toJson(result.conclusion().theoryMap()));
        conclusion.setPracticeJson(toJson(result.conclusion().practiceMap()));
        conclusion.setFrontierJson(toJson(result.conclusion().frontierMap()));
        conclusionMapper.insert(conclusion);

        if (result.note() != null) {
            session.setConclusionNote(result.note());
        }
    }

    /** 会话上下文：装载场景讨论配置（US5，缺失时知识图谱字段为空，Prompt 走通用模板零虚构）。 */
    private PromptBuilder.Context buildContext(DiscussionSession session, SimulationRun run, Scenario scenario) {
        ScenarioDiscussionProfileService.ProfileVO profile =
                profileService.findByScenarioId(session.getScenarioId());
        return new PromptBuilder.Context(
                scenario.getName(), scenario.getConcept(),
                profile == null ? null : profile.chapterSection(),
                parseParams(run), parseOutputs(run),
                profile == null ? List.of() : profile.prevKnowledge(),
                profile == null ? List.of() : profile.nextExtension(),
                profile == null ? List.of() : profile.discussionStarters(),
                profile == null ? List.of() : profile.caseLibrary(),
                profile == null ? List.of() : profile.theoryLibrary());
    }

    /** 单次 LLM 调用 + 失败重试 1 次；仍失败抛 LlmException（边界显式处理）。 */
    private String callWithRetry(List<LlmMessage> messages) {
        try {
            return truncate(requireText(llmClient.complete(messages, 0.7)));
        } catch (LlmException e) {
            log.warn("LLM 调用失败，重试一次: {}", e.getMessage());
            return truncate(requireText(llmClient.complete(messages, 0.7)));
        }
    }

    /** 空返回视为调用失败（避免 NPE 逃逸，统一走 FAILED 兜底路径）。 */
    private static String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new LlmException("LLM 返回内容为空");
        }
        return text;
    }

    private void insertUtterance(Long sessionId, int round, String role, String content, Long replyQuestionId) {
        DiscussionUtterance utterance = new DiscussionUtterance();
        utterance.setSessionId(sessionId);
        utterance.setRoundNo(round);
        utterance.setAgentRole(role);
        utterance.setContent(content);
        utterance.setReplyQuestionId(replyQuestionId);
        utteranceMapper.insert(utterance);
    }

    /** 待回应学生问题：responded=false 的最近一条（US3；无则 null）。 */
    private String loadPendingQuestion(Long sessionId) {
        DiscussionQuestion q = questionMapper.selectOne(
                new LambdaQueryWrapper<DiscussionQuestion>()
                        .eq(DiscussionQuestion::getSessionId, sessionId)
                        .eq(DiscussionQuestion::getResponded, false)
                        .orderByDesc(DiscussionQuestion::getId)
                        .last("LIMIT 1"));
        return q == null ? null : q.getContent();
    }

    private Long pendingQuestionId(Long sessionId) {
        DiscussionQuestion q = questionMapper.selectOne(
                new LambdaQueryWrapper<DiscussionQuestion>()
                        .eq(DiscussionQuestion::getSessionId, sessionId)
                        .eq(DiscussionQuestion::getResponded, false)
                        .orderByDesc(DiscussionQuestion::getId)
                        .last("LIMIT 1"));
        return q == null ? null : q.getId();
    }

    private void markQuestionResponded(Long sessionId) {
        questionMapper.update(null, new LambdaUpdateWrapper<DiscussionQuestion>()
                .eq(DiscussionQuestion::getSessionId, sessionId)
                .eq(DiscussionQuestion::getResponded, false)
                .set(DiscussionQuestion::getResponded, true));
    }

    private static String truncate(String text) {
        return text.length() > MAX_UTTERANCE_LENGTH ? text.substring(0, MAX_UTTERANCE_LENGTH) : text;
    }

    /** 会话是否已被放弃（每次落库/调用前重读，保证放弃即时生效，不产生额外发言）。 */
    private boolean isAbandoned(Long sessionId) {
        DiscussionSession fresh = sessionMapper.selectById(sessionId);
        return fresh != null && STATUS_ABANDONED.equals(fresh.getStatus());
    }

    private static boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status)
                || STATUS_ABANDONED.equals(status);
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("结论序列化失败", e);
        }
    }
}
