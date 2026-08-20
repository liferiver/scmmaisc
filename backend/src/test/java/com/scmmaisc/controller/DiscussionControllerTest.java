package com.scmmaisc.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 讨论接口契约测试（T014，H2 + Stub LLM，对齐 RunControllerTest 风格）：
 * D1 创建（202 + queuePosition）/ 400 / 404 / 403 / 409；D2 状态轮询（roundNo/utteranceCount/
 * abandonable）；D3 完整记录（snapshot/rounds/questions/conclusion 三段 4 要素）；
 * D5 放弃（ABANDONED + 放弃后不再产生发言）；D4 插话（201/400/409/403，T036）；
 * D6 历史列表（clientId 隔离/倒序/分页/字段契约）；D7 Markdown 导出（附件头 + 结构，T038）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiscussionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChapterMapper chapterMapper;

    @Autowired
    private ScenarioMapper scenarioMapper;

    @Autowired
    private SimulationRunMapper runMapper;

    @Autowired
    private DiscussionSessionMapper sessionMapper;

    @Autowired
    private DiscussionUtteranceMapper utteranceMapper;

    @Autowired
    private DiscussionQuestionMapper questionMapper;

    @Autowired
    private DiscussionConclusionMapper conclusionMapper;

    @MockBean
    private LlmClient llmClient;

    /** 测试期间创建的阻塞闸门：@AfterEach 统一放行，避免异步线程泄漏。 */
    private final List<CountDownLatch> gates = new CopyOnWriteArrayList<>();

    private long completedRunId;
    private long runningRunId;
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
    }

    @AfterEach
    void releaseGates() throws InterruptedException {
        gates.forEach(CountDownLatch::countDown);
        Thread.sleep(300); // 让被放行的异步线程完成收尾
    }

    @Test
    @DisplayName("D1: POST /api/discussions → 202 + sessionId + QUEUED + queuePosition=1")
    void createReturns202WithQueuePosition() throws Exception {
        stubStableLlm();
        long sessionId = createDiscussion(completedRunId, "client-1");
        assertTrue(sessionId > 0, "sessionId 应大于 0");
        awaitStatus(sessionId, "client-1", "COMPLETED", 10_000);
    }

    @Test
    @DisplayName("D1: 契约错误 400（runId/clientId 必填、白名单）/ 404 / 403 / 409")
    void createContractErrors() throws Exception {
        // runId 缺失
        mockMvc.perform(post("/api/discussions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"client-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        // clientId 白名单（长度不足）
        mockMvc.perform(post("/api/discussions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":" + completedRunId + ",\"clientId\":\"ab\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        // run 不存在
        mockMvc.perform(post("/api/discussions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":999999,\"clientId\":\"client-1\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
        // 归属不符
        mockMvc.perform(post("/api/discussions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":" + completedRunId + ",\"clientId\":\"client-2\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        // run 未完成
        mockMvc.perform(post("/api/discussions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":" + runningRunId + ",\"clientId\":\"client-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("D2: 状态轮询至 COMPLETED：roundNo=6、utteranceCount=20、abandonable=false")
    void statusPollingToCompleted() throws Exception {
        stubStableLlm();
        long sessionId = createDiscussion(completedRunId, "client-1");
        awaitStatus(sessionId, "client-1", "COMPLETED", 10_000);
        JsonNode data = fetchStatus(sessionId, "client-1");
        assertEquals(6, data.path("roundNo").asInt(), "结论生成中 roundNo=6");
        assertEquals(20, data.path("utteranceCount").asInt(), "5 轮 × 4 角色");
        assertEquals(false, data.path("abandonable").asBoolean(), "终态不可放弃");
        assertEquals("CH2-003", data.path("moduleId").asText());
        assertEquals("EOQ经济订货批量", data.path("scenarioName").asText());
    }

    @Test
    @DisplayName("D2: 归属 40301、不存在 40401")
    void statusOwnershipErrors() throws Exception {
        stubStableLlm();
        long sessionId = createDiscussion(completedRunId, "client-1");
        awaitStatus(sessionId, "client-1", "COMPLETED", 10_000);
        mockMvc.perform(get("/api/discussions/{id}", sessionId).param("clientId", "client-2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(get("/api/discussions/{id}", 999_999L).param("clientId", "client-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("D3: record 结构完整：snapshot 快照、5 轮 × 4 角色固定顺序、结论三段各 4 要素")
    void recordHasFullStructure() throws Exception {
        stubStableLlm();
        long sessionId = createDiscussion(completedRunId, "client-1");
        awaitStatus(sessionId, "client-1", "COMPLETED", 10_000);

        JsonNode data = fetchRecord(sessionId, "client-1");
        // snapshot：运行参数/种子/输出（SC-005 可回溯）
        assertEquals(10000, data.path("snapshot").path("params").path("annual_demand").asInt());
        assertEquals(42, data.path("snapshot").path("seed").asLong());
        assertEquals("q_star", data.path("snapshot").path("outputs").get(0).path("key").asText());
        assertEquals(1000, data.path("snapshot").path("outputs").get(0).path("value").asInt());
        // rounds：五轮标题 + 每轮固定角色顺序
        assertEquals(5, data.path("rounds").size());
        JsonNode round1 = data.path("rounds").get(0);
        assertEquals(1, round1.path("roundNo").asInt());
        assertEquals("现象解读", round1.path("title").asText());
        assertEquals(4, round1.path("utterances").size());
        assertEquals("JING", round1.path("utterances").get(0).path("agentRole").asText());
        assertEquals("HUO", round1.path("utterances").get(1).path("agentRole").asText());
        assertEquals("LIU", round1.path("utterances").get(2).path("agentRole").asText());
        assertEquals("ZHONG", round1.path("utterances").get(3).path("agentRole").asText());
        assertEquals(0, data.path("questions").size(), "无学生提问时 questions 为空");
        // conclusion：三维结论各 4 要素
        JsonNode conclusion = data.path("conclusion");
        assertEquals(4, conclusion.path("theory").size());
        assertEquals(4, conclusion.path("practice").size());
        assertEquals(4, conclusion.path("frontier").size());
        assertEquals("理论核心模型", conclusion.path("theory").path("coreModel").asText());
        assertEquals("实操参数翻译", conclusion.path("practice").path("paramBusiness").asText());
        assertEquals("兴趣投票", conclusion.path("frontier").path("voteItem").asText());
    }

    @Test
    @DisplayName("D5: 运行中放弃 → ABANDONED；重复放弃 40901；放弃后不再产生发言")
    void abandonStopsExecution() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        when(llmClient.complete(anyList(), anyDouble())).thenAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS);
            return "发言内容";
        });
        long sessionId = createDiscussion(completedRunId, "client-1");
        awaitStatus(sessionId, "client-1", "RUNNING", 10_000);

        mockMvc.perform(post("/api/discussions/{id}/abandon", sessionId).param("clientId", "client-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("ABANDONED"));
        mockMvc.perform(post("/api/discussions/{id}/abandon", sessionId).param("clientId", "client-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        gate.countDown();
        Thread.sleep(500);
        JsonNode data = fetchStatus(sessionId, "client-1");
        assertEquals("ABANDONED", data.path("status").asText(), "终态不被覆盖");
        assertEquals(0, data.path("utteranceCount").asInt(), "放弃后不再生成发言");
    }

    // ---- D4 插话（US3，T036） ----

    @Test
    @DisplayName("D4: 提交插话 → 201 + {questionId, roundNo, truncated}；超 200 字截断落库")
    void submitQuestionReturns201WithTruncation() throws Exception {
        // 直插 QUEUED 会话（不启动编排线程）：断言响应契约，避免异步线程泄漏
        long sessionId = insertSession(completedRunId, "client-1", "QUEUED", 0);

        mockMvc.perform(post("/api/discussions/{id}/questions", sessionId)
                        .param("clientId", "client-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"安全库存怎么定？\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.questionId").isNumber())
                .andExpect(jsonPath("$.data.roundNo").value(0))
                .andExpect(jsonPath("$.data.truncated").value(false));

        // 超 200 字 → truncated=true，库内恰 200 字
        String longContent = "问".repeat(201);
        MvcResult res = mockMvc.perform(post("/api/discussions/{id}/questions", sessionId)
                        .param("clientId", "client-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + longContent + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andReturn();
        long questionId = objectMapper.readTree(
                        res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("questionId").asLong();
        DiscussionQuestion stored = questionMapper.selectById(questionId);
        assertEquals(200, stored.getContent().length(), "库内恰 200 字");
    }

    @Test
    @DisplayName("D4: 契约错误 —— 空白 40001、终态 40901、归属 40301")
    void submitQuestionContractErrors() throws Exception {
        long sessionId = insertSession(completedRunId, "client-1", "QUEUED", 0);

        mockMvc.perform(post("/api/discussions/{id}/questions", sessionId)
                        .param("clientId", "client-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        long doneId = insertSession(completedRunId, "client-1", "COMPLETED", 6);
        mockMvc.perform(post("/api/discussions/{id}/questions", doneId)
                        .param("clientId", "client-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"结束后还能问吗\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        mockMvc.perform(post("/api/discussions/{id}/questions", sessionId)
                        .param("clientId", "client-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"他人提问\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ---- D6 历史列表 / D7 导出（US4，T038） ----

    @Test
    @DisplayName("D6: GET /api/discussions/history —— clientId 隔离、时间倒序、分页、字段契约")
    void historyReturnsContractShape() throws Exception {
        // client-1：A 先建（COMPLETED）、B 后建（RUNNING，2 条发言）；client-2：C
        long sessionA = insertSession(completedRunId, "client-1", "COMPLETED", 6,
                LocalDateTime.now().minusSeconds(10));
        long sessionB = insertSession(completedRunId, "client-1", "RUNNING", 2, LocalDateTime.now());
        insertUtterance(sessionB, 1, "JING", "发言1", null);
        insertUtterance(sessionB, 1, "HUO", "发言2", null);
        insertSession(completedRunId, "client-2", "COMPLETED", 6, LocalDateTime.now().minusSeconds(5));

        // 全量：2 条，创建时间倒序 B 在前，字段齐全
        mockMvc.perform(get("/api/discussions/history").param("clientId", "client-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].sessionId").value(sessionB))
                .andExpect(jsonPath("$.data.items[1].sessionId").value(sessionA))
                .andExpect(jsonPath("$.data.items[0].moduleId").value("CH2-003"))
                .andExpect(jsonPath("$.data.items[0].scenarioName").value("EOQ经济订货批量"))
                .andExpect(jsonPath("$.data.items[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.items[0].roundNo").value(2))
                .andExpect(jsonPath("$.data.items[0].utteranceCount").value(2))
                .andExpect(jsonPath("$.data.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].finishedAt").value(nullValue()));

        // scenarioId 过滤（不存在的场景 → 0 条）
        mockMvc.perform(get("/api/discussions/history").param("clientId", "client-1")
                        .param("scenarioId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // 分页 page=2&size=1 → 仅剩 A；clientId 隔离
        mockMvc.perform(get("/api/discussions/history").param("clientId", "client-1")
                        .param("page", "2").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sessionId").value(sessionA));
        mockMvc.perform(get("/api/discussions/history").param("clientId", "client-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("D7: GET /api/discussions/{id}/export —— text/markdown 附件 + 完整 Markdown 结构")
    void exportReturnsMarkdownAttachment() throws Exception {
        long sessionId = insertSession(completedRunId, "client-1", "COMPLETED", 6);
        long questionId = insertQuestion(sessionId, 1, "安全库存怎么定？", true);
        insertUtterance(sessionId, 1, "JING", "景同学发言", questionId);
        insertUtterance(sessionId, 1, "HUO", "霍教授发言", null);
        insertUtterance(sessionId, 1, "LIU", "柳经理发言", null);
        insertUtterance(sessionId, 1, "ZHONG", "钟同学发言", null);
        insertConclusion(sessionId);

        MvcResult res = mockMvc.perform(get("/api/discussions/{id}/export", sessionId)
                        .param("clientId", "client-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"discussion-CH2-003-" + sessionId + ".md\""))
                .andReturn();
        String md = res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

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
        assertTrue(md.contains("投票：兴趣投票"), "投票结果（FR-014）");
        assertTrue(md.contains("导出时间"), "含导出时间");
    }

    @Test
    @DisplayName("D7: 导出契约错误 —— 非 COMPLETED 40901、归属 40301、不存在 40401")
    void exportContractErrors() throws Exception {
        long runningId = insertSession(completedRunId, "client-1", "RUNNING", 2);
        mockMvc.perform(get("/api/discussions/{id}/export", runningId).param("clientId", "client-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        long doneId = insertSession(completedRunId, "client-1", "COMPLETED", 6);
        mockMvc.perform(get("/api/discussions/{id}/export", doneId).param("clientId", "client-2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(get("/api/discussions/{id}/export", 999_999L).param("clientId", "client-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 工具 ----

    private long createDiscussion(long runId, String clientId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("clientId", clientId);
        MvcResult res = mockMvc.perform(post("/api/discussions").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.queuePosition").value(1))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("sessionId").asLong();
    }

    private JsonNode fetchStatus(long sessionId, String clientId) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/discussions/{id}", sessionId).param("clientId", clientId)).andReturn();
        assertEquals(200, res.getResponse().getStatus(), "状态轮询应 200");
        return objectMapper.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data");
    }

    private JsonNode fetchRecord(long sessionId, String clientId) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/discussions/{id}/record", sessionId).param("clientId", clientId)).andReturn();
        assertEquals(200, res.getResponse().getStatus(), "记录接口应 200");
        return objectMapper.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data");
    }

    private void awaitStatus(long sessionId, String clientId, String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(fetchStatus(sessionId, clientId).path("status").asText())) {
                return;
            }
            Thread.sleep(20);
        }
        fail("讨论未在 " + timeoutMs + "ms 内进入 " + expected
                + "（当前: " + fetchStatus(sessionId, clientId).path("status") + "）");
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
        json.put("theory", Map.of("coreModel", "理论核心模型", "derivation", "推导要点",
                "assumptions", "假设边界", "knowledgeLocation", "第2章"));
        json.put("practice", Map.of("paramBusiness", "实操参数翻译", "caseBenchmark", "案例对标",
                "simRealityGap", "仿真与现实差距", "suggestions", "落地建议"));
        json.put("frontier", Map.of("industry", "产业前沿", "academic", "学术前沿",
                "studentAdvice", "学生关注建议", "voteItem", "兴趣投票"));
        try {
            return objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 直插会话（不启动编排线程，用于确定性构造 QUEUED/COMPLETED 等状态）。 */
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

    /** 直插三维结论（12 要素，与 Stub 结论结构一致）。 */
    private void insertConclusion(long sessionId) {
        DiscussionConclusion conclusion = new DiscussionConclusion();
        conclusion.setSessionId(sessionId);
        conclusion.setTheoryJson("{\"coreModel\":\"理论核心模型\",\"derivation\":\"推导要点\","
                + "\"assumptions\":\"假设边界\",\"knowledgeLocation\":\"第2章\"}");
        conclusion.setPracticeJson("{\"paramBusiness\":\"实操参数翻译\",\"caseBenchmark\":\"案例对标\","
                + "\"simRealityGap\":\"仿真与现实差距\",\"suggestions\":\"落地建议\"}");
        conclusion.setFrontierJson("{\"industry\":\"产业前沿\",\"academic\":\"学术前沿\","
                + "\"studentAdvice\":\"学生关注建议\",\"voteItem\":\"投票：兴趣投票\"}");
        conclusionMapper.insert(conclusion);
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
