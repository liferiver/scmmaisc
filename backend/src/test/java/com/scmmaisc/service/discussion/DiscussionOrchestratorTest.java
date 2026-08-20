package com.scmmaisc.service.discussion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.DiscussionConclusion;
import com.scmmaisc.entity.DiscussionQuestion;
import com.scmmaisc.entity.DiscussionSession;
import com.scmmaisc.entity.DiscussionUtterance;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.ScenarioDiscussionProfile;
import com.scmmaisc.entity.SimulationRun;
import com.scmmaisc.llm.LlmClient;
import com.scmmaisc.llm.LlmException;
import com.scmmaisc.llm.LlmMessage;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.DiscussionConclusionMapper;
import com.scmmaisc.mapper.DiscussionQuestionMapper;
import com.scmmaisc.mapper.DiscussionSessionMapper;
import com.scmmaisc.mapper.DiscussionUtteranceMapper;
import com.scmmaisc.mapper.ScenarioDiscussionProfileMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import com.scmmaisc.mapper.SimulationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * 五轮编排测试（T011，宪法 II：Stub 注入确定性，无网络/时序依赖）：
 * 五轮 × 四角色发言齐全且顺序固定、逐条落库、结论 12 要素生成、LLM 失败重试后
 * 会话 FAILED 且已生成内容保留（FR-011）、学生插话注入下一轮全部角色（US3，T031）。
 */
@SpringBootTest
class DiscussionOrchestratorTest {

    @Autowired
    private DiscussionOrchestrator orchestrator;

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

    @Autowired
    private ScenarioDiscussionProfileMapper profileMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LlmClient llmClient;

    private long sessionId;
    private long scenarioId;

    @BeforeEach
    void setUp() throws Exception {
        utteranceMapper.delete(null);
        conclusionMapper.delete(null);
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

        SimulationRun run = new SimulationRun();
        run.setScenarioId(scenario.getId());
        run.setClientId("client-1");
        run.setParams("{\"annual_demand\":10000,\"order_cost\":100,\"holding_cost\":2}");
        run.setSeed(42L);
        run.setStatus("COMPLETED");
        run.setStepTotal(5);
        run.setStepCount(5);
        run.setResult("[{\"key\":\"q_star\",\"label\":\"EOQ最优订货量\",\"value\":1000,\"unit\":\"件\"}]");
        runMapper.insert(run);

        DiscussionSession session = new DiscussionSession();
        session.setRunId(run.getId());
        session.setScenarioId(scenario.getId());
        session.setClientId("client-1");
        session.setStatus(DiscussionOrchestrator.STATUS_QUEUED);
        session.setRoundNo(0);
        session.setQueuePosition(1);
        session.setUpdatedAt(java.time.LocalDateTime.now());
        sessionMapper.insert(session);
        sessionId = session.getId();
    }

    @Test
    @DisplayName("五轮 × 四角色发言齐全，顺序固定 JING/HUO/LIU/ZHONG，状态 COMPLETED")
    void fiveRoundsAllRolesSpeak() {
        stubStableLlm();
        orchestrator.run(sessionId);

        DiscussionSession session = sessionMapper.selectById(sessionId);
        assertEquals(DiscussionOrchestrator.STATUS_COMPLETED, session.getStatus());
        assertEquals(6, session.getRoundNo(), "结论生成中 roundNo=6");

        List<DiscussionUtterance> utterances = utteranceMapper.selectList(
                new LambdaQueryWrapper<DiscussionUtterance>()
                        .eq(DiscussionUtterance::getSessionId, sessionId)
                        .orderByAsc(DiscussionUtterance::getId));
        assertEquals(20, utterances.size(), "5 轮 × 4 角色 = 20 条发言");
        for (int round = 1; round <= 5; round++) {
            int currentRound = round;
            List<String> roles = utterances.stream()
                    .filter(u -> u.getRoundNo() == currentRound)
                    .map(DiscussionUtterance::getAgentRole)
                    .toList();
            assertEquals(List.of("JING", "HUO", "LIU", "ZHONG"), roles, "第 " + currentRound + " 轮发言顺序固定");
        }
        DiscussionConclusion conclusion = conclusionMapper.selectOne(
                new LambdaQueryWrapper<DiscussionConclusion>().eq(DiscussionConclusion::getSessionId, sessionId));
        assertNotNull(conclusion, "结论必须生成");
        assertTrue(conclusion.getTheoryJson().contains("coreModel"), "理论结论含 4 要素");
        assertTrue(conclusion.getPracticeJson().contains("paramBusiness"), "实操结论含 4 要素");
        assertTrue(conclusion.getFrontierJson().contains("voteItem"), "前沿结论含兴趣投票");
        assertTrue(conclusion.getTheoryJson().contains("10000"), "结论引用运行参数（SC-005 数据一致）");
    }

    @Test
    @DisplayName("结论生成文本可带 markdown 围栏（结论解析容错）")
    void conclusionWithCodeFence() {
        when(llmClient.complete(anyList(), anyDouble())).thenAnswer(inv -> {
            List<LlmMessage> messages = inv.getArgument(0);
            String last = messages.get(messages.size() - 1).content();
            if (last.contains("【结论生成】")) {
                return "```json\n" + stubConclusionJson() + "\n```";
            }
            return "发言内容";
        });
        orchestrator.run(sessionId);
        DiscussionSession session = sessionMapper.selectById(sessionId);
        assertEquals(DiscussionOrchestrator.STATUS_COMPLETED, session.getStatus());
        assertEquals(20, utteranceMapper.selectCount(
                new LambdaQueryWrapper<DiscussionUtterance>().eq(DiscussionUtterance::getSessionId, sessionId)));
    }

    @Test
    @DisplayName("LLM 连续失败 → 会话 FAILED，已生成发言保留（FR-011）")
    void llmFailureLeavesSessionFailedWithContent() {
        when(llmClient.complete(anyList(), anyDouble()))
                .thenReturn("第一条发言")
                .thenThrow(new LlmException("模拟 LLM 故障"));
        orchestrator.run(sessionId);

        DiscussionSession session = sessionMapper.selectById(sessionId);
        assertEquals(DiscussionOrchestrator.STATUS_FAILED, session.getStatus());
        assertNotNull(session.getFinishedAt());
        List<DiscussionUtterance> utterances = utteranceMapper.selectList(
                new LambdaQueryWrapper<DiscussionUtterance>().eq(DiscussionUtterance::getSessionId, sessionId));
        assertTrue(utterances.size() >= 1, "失败前已生成发言必须保留");
    }

    @Test
    @DisplayName("US2：四角色每轮均发言 —— 按角色分组恰 5 条且覆盖轮次 1-5（SC-003 无角色缺失）")
    void eachRoleSpeaksEveryRound() {
        stubStableLlm();
        orchestrator.run(sessionId);

        List<DiscussionUtterance> utterances = utteranceMapper.selectList(
                new LambdaQueryWrapper<DiscussionUtterance>()
                        .eq(DiscussionUtterance::getSessionId, sessionId)
                        .orderByAsc(DiscussionUtterance::getId));
        Map<String, List<Integer>> roundsByRole = utterances.stream()
                .collect(Collectors.groupingBy(DiscussionUtterance::getAgentRole,
                        Collectors.mapping(DiscussionUtterance::getRoundNo, Collectors.toList())));
        for (String role : List.of("JING", "HUO", "LIU", "ZHONG")) {
            List<Integer> rounds = roundsByRole.get(role);
            assertNotNull(rounds, role + " 必须有发言");
            assertEquals(5, rounds.size(), role + " 恰 5 条发言");
            assertEquals(List.of(1, 2, 3, 4, 5), rounds.stream().sorted().toList(),
                    role + " 覆盖全部轮次，无缺失无错位");
        }
    }

    @Test
    @DisplayName("终态会话重复执行幂等（不产生重复发言）")
    void rerunOnTerminalSessionIsIdempotent() {
        stubStableLlm();
        orchestrator.run(sessionId);
        orchestrator.run(sessionId);
        assertEquals(20, utteranceMapper.selectCount(
                new LambdaQueryWrapper<DiscussionUtterance>().eq(DiscussionUtterance::getSessionId, sessionId)),
                "重复执行不应追加发言");
    }

    @Test
    @DisplayName("US3: 第 1 轮提交的插话注入第 2 轮全部角色（最近一条优先），回应后 responded=true")
    void pendingQuestionInjectedIntoNextRoundAllRoles() {
        // 记录每次调用的 user 消息（注入断言依据）；questionIds[0]=先提交、[1]=后提交（最近）
        List<String> userMessages = new CopyOnWriteArrayList<>();
        long[] questionIds = new long[2];
        when(llmClient.complete(anyList(), anyDouble())).thenAnswer(inv -> {
            List<LlmMessage> messages = inv.getArgument(0);
            String last = messages.get(messages.size() - 1).content();
            userMessages.add(last);
            if (questionIds[0] == 0L) {
                // 模拟：第 1 轮首个 LLM 调用期间学生先后提交两条插话
                questionIds[0] = insertQuestion("安全库存怎么定？");
                questionIds[1] = insertQuestion("补货周期能否缩短？");
            }
            if (last.contains("【结论生成】")) {
                return stubConclusionJson();
            }
            return "确定性发言：基于数据摘要的讨论内容";
        });

        orchestrator.run(sessionId);

        // 1) 仅第 2 轮 4 条发言带 replyQuestionId，且全部指向最近一条问题（US3-AC4）
        List<DiscussionUtterance> utterances = utteranceMapper.selectList(
                new LambdaQueryWrapper<DiscussionUtterance>()
                        .eq(DiscussionUtterance::getSessionId, sessionId)
                        .orderByAsc(DiscussionUtterance::getId));
        assertEquals(20, utterances.size());
        assertTrue(utterances.stream().filter(u -> u.getRoundNo() == 1)
                        .allMatch(u -> u.getReplyQuestionId() == null), "第 1 轮发言不携带问题");
        assertTrue(utterances.stream().filter(u -> u.getRoundNo() == 2)
                        .allMatch(u -> questionIds[1] == u.getReplyQuestionId()), "第 2 轮 4 条发言均回应最近一条问题");
        assertTrue(utterances.stream().filter(u -> u.getRoundNo() >= 3)
                        .allMatch(u -> u.getReplyQuestionId() == null), "第 3 轮起无待回应问题");

        // 2) 注入出现在 user 消息头部：恰 4 条（第 2 轮全部角色），内容为最近一条
        long injectedCount = userMessages.stream().filter(m -> m.contains("本轮需回应学生问题")).count();
        assertEquals(4, injectedCount, "仅第 2 轮 4 个角色收到注入");
        assertTrue(userMessages.stream().anyMatch(m -> m.contains("本轮需回应学生问题：补货周期能否缩短？")),
                "注入内容为最近一条问题");
        assertTrue(userMessages.stream().noneMatch(m -> m.contains("安全库存怎么定？")),
                "同轮多条时仅最近一条优先注入（US3-AC4）");

        // 3) 回应后该轮全部待回应问题标记 responded=true
        List<DiscussionQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<DiscussionQuestion>().eq(DiscussionQuestion::getSessionId, sessionId));
        assertEquals(2, questions.size());
        assertTrue(questions.stream().allMatch(q -> Boolean.TRUE.equals(q.getResponded())), "回应后 responded=true");
    }

    @Test
    @DisplayName("US5: 有配置时 R3 注入前序/后续元数据，system 含案例/理论库索引（stub 下跨章引用与元数据一致）")
    void profileMetadataReachesPrompt() {
        insertProfile();
        List<String> userMessages = new CopyOnWriteArrayList<>();
        List<String> systemMessages = new CopyOnWriteArrayList<>();
        when(llmClient.complete(anyList(), anyDouble())).thenAnswer(inv -> {
            List<LlmMessage> messages = inv.getArgument(0);
            systemMessages.add(messages.get(0).content());
            userMessages.add(messages.get(messages.size() - 1).content());
            String last = messages.get(messages.size() - 1).content();
            if (last.contains("【结论生成】")) {
                return stubConclusionJson();
            }
            return "确定性发言：基于数据摘要的讨论内容";
        });

        orchestrator.run(sessionId);

        // 跨章连接轮恰 4 条注入（全部角色），且与人工覆盖元数据一致
        List<String> r3 = userMessages.stream().filter(m -> m.contains("【第3轮：跨章连接】")).toList();
        assertEquals(4, r3.size(), "R3 全部角色收到链路元数据");
        assertTrue(r3.stream().allMatch(m -> m.contains("前序知识：需求预测（教材第2章）")), "前序与元数据一致");
        assertTrue(r3.stream().allMatch(m -> m.contains("后续延伸：随机需求库存(s,Q)策略仿真、啤酒游戏——牛鞭效应")), "后续与元数据一致");
        // 跨章引用仅来自给定元数据，无虚构其他章节
        assertTrue(r3.stream().noneMatch(m -> m.contains("海外仓")), "无虚构章节引用（SC-010）");
        // system 含章节定位与人工覆盖差异化内容（案例/理论库索引，US2-AC4 支撑输入）
        assertTrue(systemMessages.stream().allMatch(m -> m.contains("章节定位：第二章 物流系统控制")));
        assertTrue(systemMessages.stream().anyMatch(m -> m.contains("案例库索引：教材案例：制造业采购批量决策")));
        assertTrue(systemMessages.stream().anyMatch(m -> m.contains("理论库索引：EOQ 模型推导与假设边界")));
    }

    @Test
    @DisplayName("US5-AC3: 无配置场景 R3 走通用引导模板（零虚构章节引用）")
    void noProfileRound3UsesGenericTemplate() {
        List<String> userMessages = new CopyOnWriteArrayList<>();
        when(llmClient.complete(anyList(), anyDouble())).thenAnswer(inv -> {
            List<LlmMessage> messages = inv.getArgument(0);
            userMessages.add(messages.get(messages.size() - 1).content());
            String last = messages.get(messages.size() - 1).content();
            if (last.contains("【结论生成】")) {
                return stubConclusionJson();
            }
            return "确定性发言：基于数据摘要的讨论内容";
        });

        orchestrator.run(sessionId);

        List<String> r3 = userMessages.stream().filter(m -> m.contains("【第3轮：跨章连接】")).toList();
        assertEquals(4, r3.size());
        assertTrue(r3.stream().allMatch(m -> m.contains("本场景未提供链路元数据，仅作概括性关联，不得引用具体章节名")),
                "无配置不虚构章节引用（US5-AC3）");
    }

    /** 直插学生插话（responded=false，提交轮次 1）。 */
    private long insertQuestion(String content) {
        DiscussionQuestion question = new DiscussionQuestion();
        question.setSessionId(sessionId);
        question.setRoundNo(1);
        question.setContent(content);
        question.setResponded(false);
        question.setCreatedAt(LocalDateTime.now());
        questionMapper.insert(question);
        return question.getId();
    }

    /** 人工覆盖配置（MANUAL，US5）：前序/后续 + 案例/理论库索引。 */
    private void insertProfile() {
        ScenarioDiscussionProfile p = new ScenarioDiscussionProfile();
        p.setScenarioId(scenarioId);
        p.setModuleId("CH2-003");
        p.setConceptTags("[\"经济订货批量\"]");
        p.setChapterSection("第二章 物流系统控制");
        p.setPrevKnowledge("[\"需求预测（教材第2章）\"]");
        p.setNextExtension("[\"随机需求库存(s,Q)策略仿真\",\"啤酒游戏——牛鞭效应\"]");
        p.setDiscussionStarters("[\"比较 Q* 与实际订货量的成本差异\"]");
        p.setCaseLibrary("[\"教材案例：制造业采购批量决策\"]");
        p.setTheoryLibrary("[\"EOQ 模型推导与假设边界\"]");
        p.setSource("MANUAL");
        profileMapper.insert(p);
    }

    private void stubStableLlm() {
        when(llmClient.complete(anyList(), anyDouble())).thenAnswer(inv -> {
            List<LlmMessage> messages = inv.getArgument(0);
            String last = messages.get(messages.size() - 1).content();
            if (last.contains("【结论生成】")) {
                return stubConclusionJson();
            }
            return "确定性发言：基于数据摘要的讨论内容";
        });
    }

    private String stubConclusionJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("theory", Map.of("coreModel", "模型", "derivation", "推导：annual_demand=10000",
                "assumptions", "假设", "knowledgeLocation", "第2章"));
        json.put("practice", Map.of("paramBusiness", "参数业务翻译", "caseBenchmark", "案例",
                "simRealityGap", "差距", "suggestions", "建议"));
        json.put("frontier", Map.of("industry", "产业", "academic", "学术",
                "studentAdvice", "建议", "voteItem", "投票"));
        try {
            return objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
