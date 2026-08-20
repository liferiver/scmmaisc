package com.scmmaisc.service.discussion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.scmmaisc.common.BizException;
import com.scmmaisc.common.ErrorCode;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.DiscussionConclusion;
import com.scmmaisc.entity.DiscussionQuestion;
import com.scmmaisc.entity.DiscussionSession;
import com.scmmaisc.entity.DiscussionUtterance;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.SimulationRun;
import com.scmmaisc.llm.LlmClient;
import com.scmmaisc.llm.LlmMessage;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.DiscussionConclusionMapper;
import com.scmmaisc.mapper.DiscussionQuestionMapper;
import com.scmmaisc.mapper.DiscussionSessionMapper;
import com.scmmaisc.mapper.DiscussionUtteranceMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import com.scmmaisc.mapper.SimulationRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 讨论服务测试（T013/T030/T037）：D1 创建校验（runId/clientId 必填、clientId 白名单、run 存在、
 * 归属 403、非 COMPLETED 409）、D2 状态机流转（QUEUED→RUNNING→COMPLETED/ABANDONED）、
 * 同一 run 多次创建各自独立、放弃后不再产生发言（D5 语义，H2 + Stub LLM 确定性）、
 * D4 插话校验（空白 400、超 200 字截断、终态 409、归属 403、round_no 记录提交时轮次）、
 * D6 历史列表（clientId/scenarioId 过滤、时间倒序、分页）、D7 Markdown 导出结构（US4）。
 */
@SpringBootTest
class DiscussionServiceTest {

    @Autowired
    private DiscussionService discussionService;

    @Autowired
    private DiscussionSessionMapper sessionMapper;

    @Autowired
    private DiscussionUtteranceMapper utteranceMapper;

    @Autowired
    private DiscussionConclusionMapper conclusionMapper;

    @Autowired
    private DiscussionQuestionMapper questionMapper;

    @Autowired
    private ChapterMapper chapterMapper;

    @Autowired
    private ScenarioMapper scenarioMapper;

    @Autowired
    private SimulationRunMapper runMapper;

    @MockBean
    private LlmClient llmClient;

    /** 测试期间创建的阻塞闸门：@AfterEach 统一放行，避免异步线程泄漏到后续用例。 */
    private final List<CountDownLatch> gates = new CopyOnWriteArrayList<>();

    private long completedRunId;
    private long runningRunId;
    private long otherClientRunId;
    private long scenarioId;

    @BeforeEach
    void setUp() {
        conclusionMapper.delete(null);
        // 先删发言再删问题：fk_utterance_question 无级联，倒序会违反引用完整性
        utteranceMapper.delete(null);
        questionMapper.delete(null);
        sessionMapper.delete(null);
        runMapper.delete(null);
        scenarioMapper.delete(null);
        chapterMapper.delete(null);

        Chapter chapter = new Chapter();
        chapter.setCode("CH2");
        chapter.setName("物流系统控制");
        chapter.setSortNo(2);
        chapterMapper.insert(chapter);

        Scenario scenario = new Scenario();
        scenario.setChapterId(chapter.getId());
        scenario.setModuleId("CH2-003");
        scenario.setName("EOQ经济订货批量");
        scenario.setEngineKey("eoq");
        scenario.setDifficulty("intro");
        scenario.setClassHours(2);
        scenario.setIsRolePlay(false);
        scenario.setConcept("库存管理,经济订货批量");
        scenario.setDescription("流程");
        scenario.setDeps("[]");
        scenario.setParams("[]");
        scenario.setOutputs("[]");
        scenario.setConstraints("[]");
        scenarioMapper.insert(scenario);
        scenarioId = scenario.getId();

        completedRunId = insertRun(scenario.getId(), "client-1", "COMPLETED");
        runningRunId = insertRun(scenario.getId(), "client-1", "RUNNING");
        otherClientRunId = insertRun(scenario.getId(), "client-2", "COMPLETED");
    }

    @AfterEach
    void releaseGates() throws InterruptedException {
        gates.forEach(CountDownLatch::countDown);
        Thread.sleep(300); // 让被放行的异步线程完成收尾（finally 移出排队集合）
    }

    @Test
    @DisplayName("D1: runId/clientId 必填，clientId 白名单校验 → 40001")
    void createValidatesRunIdAndClientId() {
        BizException ex = assertThrows(BizException.class,
                () -> discussionService.create(new DiscussionService.CreateDiscussionRequest(null, "client-1")));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode(), "runId 必填");
        assertThrows(BizException.class,
                () -> discussionService.create(new DiscussionService.CreateDiscussionRequest(completedRunId, "ab")),
                "clientId 长度不足");
        assertThrows(BizException.class,
                () -> discussionService.create(new DiscussionService.CreateDiscussionRequest(completedRunId, null)),
                "clientId 必填");
    }

    @Test
    @DisplayName("D1: run 不存在 40401、归属不符 40301、run 未完成 40901")
    void createRejectsInvalidRunStates() {
        BizException ex = assertThrows(BizException.class,
                () -> discussionService.create(new DiscussionService.CreateDiscussionRequest(999_999L, "client-1")));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        ex = assertThrows(BizException.class,
                () -> discussionService.create(new DiscussionService.CreateDiscussionRequest(completedRunId, "client-2")));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        ex = assertThrows(BizException.class,
                () -> discussionService.create(new DiscussionService.CreateDiscussionRequest(runningRunId, "client-1")));
        assertEquals(ErrorCode.STATE_CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("D1+D2: 创建 202 + queuePosition，状态机 QUEUED→RUNNING→COMPLETED（roundNo=6，20 条发言）")
    void createToCompletionStateMachine() throws Exception {
        stubStableLlm();
        DiscussionService.CreateDiscussionResult created = create(completedRunId, "client-1");
        assertNotNull(created.sessionId());
        assertEquals(DiscussionOrchestrator.STATUS_QUEUED, created.status());
        assertEquals(1, created.queuePosition(), "空队列首个排队位置为 1");

        DiscussionService.DiscussionStatusVO terminal =
                awaitStatus(created.sessionId(), "client-1", vo -> DiscussionOrchestrator.STATUS_COMPLETED.equals(vo.status()),
                        10_000);
        assertFalse(terminal.abandonable(), "终态不可放弃");
        assertEquals(DiscussionOrchestrator.STATUS_COMPLETED, terminal.status());
        assertEquals(6, terminal.roundNo(), "结论生成中 roundNo=6");
        assertEquals(20, terminal.utteranceCount(), "5 轮 × 4 角色 = 20 条");
        assertEquals(0, terminal.questionCount());
        assertEquals("CH2-003", terminal.moduleId());
        assertEquals("EOQ经济订货批量", terminal.scenarioName());
        assertEquals(completedRunId, terminal.runId());
    }

    @Test
    @DisplayName("D2/D5: 归属校验 40301，不存在 40401，已终态再放弃 40901")
    void statusAndAbandonOwnershipChecks() throws Exception {
        stubStableLlm();
        long sessionId = create(completedRunId, "client-1").sessionId();
        awaitStatus(sessionId, "client-1", vo -> DiscussionOrchestrator.STATUS_COMPLETED.equals(vo.status()), 10_000);

        BizException ex = assertThrows(BizException.class, () -> discussionService.status(sessionId, "client-2"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        ex = assertThrows(BizException.class, () -> discussionService.status(999_999L, "client-1"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        ex = assertThrows(BizException.class, () -> discussionService.abandon(sessionId, "client-1"));
        assertEquals(ErrorCode.STATE_CONFLICT, ex.getErrorCode(), "COMPLETED 已终态不可放弃");
    }

    @Test
    @DisplayName("D5: 运行中放弃 → ABANDONED，放弃后不再产生发言，终态保留")
    void abandonStopsFurtherExecution() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        when(llmClient.complete(anyList(), anyDouble())).thenAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS);
            return "发言内容";
        });

        long sessionId = create(completedRunId, "client-1").sessionId();
        awaitStatus(sessionId, "client-1", vo -> DiscussionOrchestrator.STATUS_RUNNING.equals(vo.status()), 10_000);

        DiscussionService.AbandonResult abandoned = discussionService.abandon(sessionId, "client-1");
        assertEquals(DiscussionOrchestrator.STATUS_ABANDONED, abandoned.status());

        gate.countDown();
        Thread.sleep(500);

        DiscussionService.DiscussionStatusVO after = discussionService.status(sessionId, "client-1");
        assertEquals(DiscussionOrchestrator.STATUS_ABANDONED, after.status(), "终态不被 RUNNING/COMPLETED 覆盖");
        assertEquals(0, after.utteranceCount(), "放弃后不再生成发言");
        assertFalse(after.abandonable());
    }

    @Test
    @DisplayName("同一 run 多次创建 → 会话各自独立，互不影响地完成")
    void multipleSessionsPerRunIndependent() throws Exception {
        stubStableLlm();
        DiscussionService.CreateDiscussionResult a = create(completedRunId, "client-1");
        DiscussionService.CreateDiscussionResult b = create(completedRunId, "client-1");
        assertNotEquals(a.sessionId(), b.sessionId(), "同一 run 每次创建独立会话");
        assertNotNull(a.queuePosition());
        assertNotNull(b.queuePosition());
        assertTrue(b.queuePosition() >= a.queuePosition(), "后创建的排队位置不小于先创建的");

        awaitStatus(a.sessionId(), "client-1", vo -> DiscussionOrchestrator.STATUS_COMPLETED.equals(vo.status()), 10_000);
        awaitStatus(b.sessionId(), "client-1", vo -> DiscussionOrchestrator.STATUS_COMPLETED.equals(vo.status()), 10_000);
        assertEquals(20, countUtterances(a.sessionId()));
        assertEquals(20, countUtterances(b.sessionId()));
    }

    // ---- D4 插话（US3，T030） ----

    @Test
    @DisplayName("D4: 空白/空内容提交 → 40001「问题不能为空」，不落库")
    void submitQuestionRejectsBlankContent() {
        // 直插 QUEUED 会话（不启动编排线程）：仅验证服务校验逻辑，避免异步线程泄漏
        long sessionId = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_QUEUED, 0);

        BizException ex = assertThrows(BizException.class, () -> discussionService.submitQuestion(
                sessionId, "client-1", new DiscussionService.SubmitQuestionRequest("   ")));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertEquals("问题不能为空", ex.getMessage());

        ex = assertThrows(BizException.class, () -> discussionService.submitQuestion(
                sessionId, "client-1", new DiscussionService.SubmitQuestionRequest(null)));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());

        ex = assertThrows(BizException.class,
                () -> discussionService.submitQuestion(sessionId, "client-1", null));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());

        assertEquals(0, questionMapper.selectCount(new LambdaQueryWrapper<DiscussionQuestion>()
                        .eq(DiscussionQuestion::getSessionId, sessionId)),
                "空白提交不落库");
    }

    @Test
    @DisplayName("D4: 超 200 字截断存储（truncated=true，库内恰 200 字），responded=false")
    void submitQuestionTruncatesOverLength() {
        // 直插 RUNNING 会话（roundNo=1）：断言 roundNo=提交时轮次，无异步线程参与
        long sessionId = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_RUNNING, 1);
        String longContent = "库存周转天数对订货点的影响是什么？".repeat(12); // 204 字 > 200
        assertTrue(longContent.length() > 200);
        DiscussionService.SubmitQuestionResult result = discussionService.submitQuestion(
                sessionId, "client-1", new DiscussionService.SubmitQuestionRequest(longContent));

        assertTrue(result.truncated(), "超长必须标记 truncated");
        assertNotNull(result.questionId());
        assertEquals(1, result.roundNo(), "roundNo=提交时轮次");
        DiscussionQuestion stored = questionMapper.selectById(result.questionId());
        assertEquals(200, stored.getContent().length(), "库内恰 200 字");
        assertEquals(longContent.substring(0, 200), stored.getContent());
        assertEquals(Integer.valueOf(1), stored.getRoundNo());
        assertEquals(Boolean.FALSE, stored.getResponded(), "新问题待回应");
    }

    @Test
    @DisplayName("D4: 终态（COMPLETED）提交 → 40901「讨论已结束」，不落库")
    void submitQuestionTerminalSessionRejected() throws Exception {
        stubStableLlm();
        long sessionId = create(completedRunId, "client-1").sessionId();
        awaitStatus(sessionId, "client-1", vo -> DiscussionOrchestrator.STATUS_COMPLETED.equals(vo.status()),
                10_000);

        BizException ex = assertThrows(BizException.class, () -> discussionService.submitQuestion(
                sessionId, "client-1", new DiscussionService.SubmitQuestionRequest("结束后还能问吗")));
        assertEquals(ErrorCode.STATE_CONFLICT, ex.getErrorCode());
        assertEquals("讨论已结束", ex.getMessage());
        assertEquals(0, questionMapper.selectCount(new LambdaQueryWrapper<DiscussionQuestion>()
                        .eq(DiscussionQuestion::getSessionId, sessionId)),
                "终态提交不落库");
    }

    @Test
    @DisplayName("D4: 归属校验 —— 他人客户端提交 → 40301")
    void submitQuestionOwnershipForbidden() {
        long sessionId = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_RUNNING, 1);

        BizException ex = assertThrows(BizException.class, () -> discussionService.submitQuestion(
                sessionId, "client-2", new DiscussionService.SubmitQuestionRequest("他人提问")));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("D4: round_no 记录提交时轮次 —— 排队中 0、运行中第 1 轮 1")
    void submitQuestionRecordsSubmissionRound() {
        long queuedId = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_QUEUED, 0);
        DiscussionService.SubmitQuestionResult q0 = discussionService.submitQuestion(
                queuedId, "client-1", new DiscussionService.SubmitQuestionRequest("排队中提问"));
        assertEquals(0, q0.roundNo());
        assertFalse(q0.truncated());

        long runningId = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_RUNNING, 1);
        DiscussionService.SubmitQuestionResult q1 = discussionService.submitQuestion(
                runningId, "client-1", new DiscussionService.SubmitQuestionRequest("运行中提问"));
        assertEquals(1, q1.roundNo());
    }

    // ---- D6 历史列表 / D7 导出（US4，T037） ----

    @Test
    @DisplayName("D6: 历史列表 —— clientId/scenarioId 过滤、创建时间倒序、分页默认与上限")
    void historyFiltersAndPaginates() throws Exception {
        // client-1：A 先建（COMPLETED）、B 后建（RUNNING）；client-2：C
        long sessionA = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_COMPLETED, 6,
                LocalDateTime.now().minusSeconds(10));
        long sessionB = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_RUNNING, 2,
                LocalDateTime.now());
        insertSession(completedRunId, "client-2", DiscussionOrchestrator.STATUS_COMPLETED, 6,
                LocalDateTime.now().minusSeconds(5));

        // 全量（client-1）：2 条，时间倒序 B 在前，字段齐全
        DiscussionService.HistoryVO all = discussionService.history("client-1", null, null, null);
        assertEquals(2, all.total());
        assertEquals(List.of(sessionB, sessionA), all.items().stream()
                .map(DiscussionService.HistoryItemVO::sessionId).toList(), "创建时间倒序");
        DiscussionService.HistoryItemVO item = all.items().get(0);
        assertEquals("CH2-003", item.moduleId());
        assertEquals("EOQ经济订货批量", item.scenarioName());
        assertEquals(DiscussionOrchestrator.STATUS_RUNNING, item.status());
        assertEquals(2, item.roundNo());
        assertEquals(0, item.utteranceCount());
        assertNotNull(item.createdAt());
        assertNull(item.finishedAt(), "运行中无结束时间");

        // scenarioId 过滤（不存在的场景 → 0 条）
        assertEquals(0, discussionService.history("client-1", 999_999L, null, null).total());

        // 分页：page=2&size=1 → 仅剩 A；size=100 钳制不报错；clientId 隔离
        DiscussionService.HistoryVO page2 = discussionService.history("client-1", null, 2, 1);
        assertEquals(1, page2.items().size());
        assertEquals(sessionA, page2.items().get(0).sessionId());
        assertDoesNotThrow(() -> discussionService.history("client-1", null, 1, 100));
        assertEquals(1, discussionService.history("client-2", null, null, null).total(),
                "clientId 隔离，只能看到自己的会话");
    }

    @Test
    @DisplayName("D7: exportMarkdown —— 场景名/四角色发言/插话标注/12 要素键/投票结果/导出时间")
    void exportMarkdownHasFullStructure() {
        long sessionId = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_COMPLETED, 6);
        long questionId = insertQuestion(sessionId, 1, "安全库存怎么定？", true);
        insertUtterance(sessionId, 1, "JING", "景同学发言：基于数据摘要的讨论", questionId);
        insertUtterance(sessionId, 1, "HUO", "霍教授发言：理论推导", null);
        insertUtterance(sessionId, 1, "LIU", "柳经理发言：业务对标", null);
        insertUtterance(sessionId, 1, "ZHONG", "钟同学发言：继续提问", null);
        DiscussionConclusion conclusion = new DiscussionConclusion();
        conclusion.setSessionId(sessionId);
        conclusion.setTheoryJson("{\"coreModel\":\"经济订货批量模型\",\"derivation\":\"Q*=sqrt(2DS/H)\","
                + "\"assumptions\":\"需求恒定\",\"knowledgeLocation\":\"第2章\"}");
        conclusion.setPracticeJson("{\"paramBusiness\":\"参数业务翻译\",\"caseBenchmark\":\"京东案例\","
                + "\"simRealityGap\":\"仿真简化\",\"suggestions\":\"分批采购\"}");
        conclusion.setFrontierJson("{\"industry\":\"智能补货\",\"academic\":\"绿色供应链\","
                + "\"studentAdvice\":\"建议学习\",\"voteItem\":\"投票：智慧物流\"}");
        conclusionMapper.insert(conclusion);

        String md = discussionService.exportMarkdown(sessionId, "client-1").content();

        assertTrue(md.contains("EOQ经济订货批量"), "含场景名");
        for (String role : List.of("景同学", "霍教授", "柳经理", "钟同学")) {
            assertTrue(md.contains(role), "四角色发言缺 " + role);
        }
        assertTrue(md.contains("回应学生问题：安全库存怎么定？"), "插话标注");
        for (String key : List.of("coreModel", "derivation", "assumptions", "knowledgeLocation",
                "paramBusiness", "caseBenchmark", "simRealityGap", "suggestions",
                "industry", "academic", "studentAdvice", "voteItem")) {
            assertTrue(md.contains(key), "结论要素键缺失: " + key);
        }
        assertTrue(md.contains("投票：智慧物流"), "投票结果（FR-014）");
        assertTrue(md.contains("导出时间"), "含导出时间");
        assertTrue(md.contains("运行快照摘要"), "含运行快照摘要（SC-008）");
    }

    @Test
    @DisplayName("D7: 非 COMPLETED 导出 → 40901；他人客户端 → 40301")
    void exportMarkdownRejectsNonCompleted() {
        long runningId = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_RUNNING, 2);
        BizException ex = assertThrows(BizException.class,
                () -> discussionService.exportMarkdown(runningId, "client-1"));
        assertEquals(ErrorCode.STATE_CONFLICT, ex.getErrorCode());

        long doneId = insertSession(completedRunId, "client-1", DiscussionOrchestrator.STATUS_COMPLETED, 6);
        ex = assertThrows(BizException.class, () -> discussionService.exportMarkdown(doneId, "client-2"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    // ---- 工具 ----

    private DiscussionService.CreateDiscussionResult create(long runId, String clientId) {
        return discussionService.create(new DiscussionService.CreateDiscussionRequest(runId, clientId));
    }

    private DiscussionService.DiscussionStatusVO awaitStatus(Long sessionId, String clientId,
                                                             Predicate<DiscussionService.DiscussionStatusVO> condition,
                                                             long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        DiscussionService.DiscussionStatusVO vo = null;
        do {
            vo = discussionService.status(sessionId, clientId);
            if (condition.test(vo)) {
                return vo;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < deadline);
        fail("等待状态超时: sessionId=" + sessionId + ", 当前=" + vo);
        return null; // 不可达
    }

    private long countUtterances(Long sessionId) {
        return utteranceMapper.selectCount(
                new LambdaQueryWrapper<DiscussionUtterance>().eq(DiscussionUtterance::getSessionId, sessionId));
    }

    /** 直插会话（不启动编排线程，用于确定性构造 QUEUED 等状态）。 */
    private long insertSession(long runId, String clientId, String status, int roundNo) {
        DiscussionSession session = new DiscussionSession();
        session.setRunId(runId);
        session.setScenarioId(scenarioId);
        session.setClientId(clientId);
        session.setStatus(status);
        session.setRoundNo(roundNo);
        session.setQueuePosition(1);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session.getId();
    }

    /** 直插会话并指定创建时间（历史排序测试用）。 */
    private long insertSession(long runId, String clientId, String status, int roundNo, LocalDateTime createdAt) {
        long id = insertSession(runId, clientId, status, roundNo);
        DiscussionSession session = sessionMapper.selectById(id);
        session.setCreatedAt(createdAt);
        sessionMapper.updateById(session);
        return id;
    }

    /** 直插发言。 */
    private long insertUtterance(long sessionId, int round, String role, String content, Long replyQuestionId) {
        DiscussionUtterance u = new DiscussionUtterance();
        u.setSessionId(sessionId);
        u.setRoundNo(round);
        u.setAgentRole(role);
        u.setContent(content);
        u.setReplyQuestionId(replyQuestionId);
        utteranceMapper.insert(u);
        return u.getId();
    }

    /** 直插学生插话。 */
    private long insertQuestion(long sessionId, int roundNo, String content, boolean responded) {
        DiscussionQuestion question = new DiscussionQuestion();
        question.setSessionId(sessionId);
        question.setRoundNo(roundNo);
        question.setContent(content);
        question.setResponded(responded);
        question.setCreatedAt(LocalDateTime.now());
        questionMapper.insert(question);
        return question.getId();
    }

    private void stubStableLlm() {
        when(llmClient.complete(anyList(), anyDouble())).thenReturn("确定性发言：基于数据摘要的讨论内容");
    }

    private long insertRun(long scenarioId, String clientId, String status) {
        SimulationRun run = new SimulationRun();
        run.setScenarioId(scenarioId);
        run.setClientId(clientId);
        run.setParams("{\"annual_demand\":10000,\"order_cost\":100,\"holding_cost\":2}");
        run.setSeed(42L);
        run.setStatus(status);
        run.setStepTotal(5);
        run.setStepCount(5);
        run.setResult("[{\"key\":\"q_star\",\"label\":\"EOQ最优订货量\",\"value\":1000,\"unit\":\"件\"}]");
        runMapper.insert(run);
        return run.getId();
    }
}
