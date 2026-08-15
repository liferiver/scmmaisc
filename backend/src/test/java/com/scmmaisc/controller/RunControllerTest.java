package com.scmmaisc.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.engine.SimContext;
import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.Scenario;
import com.scmmaisc.entity.SimulationLog;
import com.scmmaisc.mapper.ChapterMapper;
import com.scmmaisc.mapper.ScenarioMapper;
import com.scmmaisc.mapper.SimulationLogMapper;
import com.scmmaisc.mapper.SimulationRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US2 契约测试（T021）：C4 创建运行（202+runId）/ 非法参数 400+原因 / C5 状态轮询 /
 * C7 取消 / clientId 归属 403 / 终态后 409。
 * 取消路径使用测试专用 slow 执行器（engine_key=slow，200 步 × 5ms），保证可确定性取消。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChapterMapper chapterMapper;

    @Autowired
    private ScenarioMapper scenarioMapper;

    @Autowired
    private SimulationRunMapper runMapper;

    @Autowired
    private SimulationLogMapper logMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private long eoqScenarioId;
    private long slowScenarioId;

    @BeforeEach
    void setUp() throws Exception {
        logMapper.delete(null);
        runMapper.delete(null);
        scenarioMapper.delete(null);
        chapterMapper.delete(null);

        Chapter chapter = new Chapter();
        chapter.setCode("CH2");
        chapter.setName("物流系统控制");
        chapter.setSortNo(2);
        chapterMapper.insert(chapter);

        eoqScenarioId = insertScenario("CH2-003", "EOQ 经济订货批量", "eoq");
        slowScenarioId = insertScenario("CH2-SLOW", "慢速测试场景", "slow");
    }

    @Test
    @DisplayName("C4: POST /api/runs 返回 202 与 runId，状态 RUNNING")
    void createReturns202() throws Exception {
        long runId = createRun(eoqScenarioId, "client-1", eoqParams());
        assertTrue(runId > 0, "runId 应大于 0");
        awaitStatus(runId, "client-1", "COMPLETED", 10_000);
    }

    @Test
    @DisplayName("C4: 非法参数返回 400 + 具体原因列表（不进 RUNNING）")
    void invalidParamsRejected() throws Exception {
        Map<String, Object> bad = eoqParams();
        bad.put("annual_demand", 50);
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(eoqScenarioId, "client-1", bad, 42))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("annual_demand")));
    }

    @Test
    @DisplayName("C5: 状态轮询至 COMPLETED，progress=1、stepCount≥1、stepTotal=5")
    void statusPollingToCompleted() throws Exception {
        long runId = createRun(eoqScenarioId, "client-1", eoqParams());
        awaitStatus(runId, "client-1", "COMPLETED", 10_000);
        JsonNode data = fetchStatus(runId, "client-1");
        org.junit.jupiter.api.Assertions.assertEquals(1.0, data.path("progress").asDouble(), "终态 progress=1");
        org.junit.jupiter.api.Assertions.assertTrue(data.path("stepCount").asInt() >= 1, "stepCount ≥ 1");
        org.junit.jupiter.api.Assertions.assertEquals(5, data.path("stepTotal").asInt(), "EOQ 预估 5 步");
    }

    @Test
    @DisplayName("C6: 完成后 result 返回输出指标与全部步骤日志")
    void resultAfterComplete() throws Exception {
        long runId = createRun(eoqScenarioId, "client-1", eoqParams());
        awaitStatus(runId, "client-1", "COMPLETED", 10_000);
        mockMvc.perform(get("/api/runs/{id}/result", runId).param("clientId", "client-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.seed").value(42))
                .andExpect(jsonPath("$.data.params.annual_demand").value(10000))
                .andExpect(jsonPath("$.data.outputs.length()").value(6))
                .andExpect(jsonPath("$.data.outputs[0].key").value("q_star"))
                .andExpect(jsonPath("$.data.outputs[0].value").value(1000.0))
                .andExpect(jsonPath("$.data.steps.length()").value(5))
                .andExpect(jsonPath("$.data.steps[0].stepNo").value(1))
                .andExpect(jsonPath("$.data.steps[0].eventType").value("STEP"));
    }

    @Test
    @DisplayName("C5/C6/C7: clientId 不匹配返回 403 + 40301")
    void clientIdMismatchForbidden() throws Exception {
        long runId = createRun(eoqScenarioId, "client-1", eoqParams());
        mockMvc.perform(get("/api/runs/{id}", runId).param("clientId", "intruder"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(get("/api/runs/{id}/result", runId).param("clientId", "intruder"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(delete("/api/runs/{id}", runId).param("clientId", "intruder"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("C7: 运行中取消 → 200，终态 CANCELLED，日志保留中断位置，result 409")
    void cancelRunningRun() throws Exception {
        long runId = createRun(slowScenarioId, "client-1", Map.of("rounds", 200));
        awaitStepCount(runId, "client-1", 1, 10_000);

        mockMvc.perform(delete("/api/runs/{id}", runId).param("clientId", "client-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        awaitStatus(runId, "client-1", "CANCELLED", 10_000);
        JsonNode data = fetchStatus(runId, "client-1");
        int stepCount = data.path("stepCount").asInt();
        assertTrue(stepCount < 200, "取消后应提前终止（stepCount=" + stepCount + "）");
        assertTrue(stepCount >= 1, "取消前已产生的日志应保留");

        // 取消后 result 不可用（409）
        mockMvc.perform(get("/api/runs/{id}/result", runId).param("clientId", "client-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));

        // 日志落库校验（V6）：simulation_log 保留中断前全部步骤
        List<SimulationLog> logs = logMapper.selectList(
                new LambdaQueryWrapper<SimulationLog>().eq(SimulationLog::getRunId, runId)
                        .orderByAsc(SimulationLog::getStepNo));
        org.junit.jupiter.api.Assertions.assertEquals(stepCount, logs.size(), "日志数与 stepCount 一致");
    }

    @Test
    @DisplayName("C6: 运行尚未完成时请求 result 返回 409")
    void resultWhileRunningConflict() throws Exception {
        long runId = createRun(slowScenarioId, "client-1", Map.of("rounds", 200));
        mockMvc.perform(get("/api/runs/{id}/result", runId).param("clientId", "client-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("C7: 终态后 DELETE 返回 409 + 40901")
    void deleteAfterTerminalConflict() throws Exception {
        long runId = createRun(eoqScenarioId, "client-1", eoqParams());
        awaitStatus(runId, "client-1", "COMPLETED", 10_000);
        mockMvc.perform(delete("/api/runs/{id}", runId).param("clientId", "client-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    @DisplayName("C4: scenarioId 不存在返回 404 + 40401")
    void scenarioNotFound() throws Exception {
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(999999L, "client-1", eoqParams(), 42))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    @DisplayName("T041: 缺少 scenarioId / clientId 非法 / seed 为负 均返回 400 + 40001")
    void requestShapeRejected() throws Exception {
        Map<String, Object> noScenario = body(null, "client-1", eoqParams(), 42);
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noScenario)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("scenarioId")));

        Map<String, Object> badClient = body(eoqScenarioId, "a!b", eoqParams(), 42);
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badClient)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("clientId")));

        Map<String, Object> negSeed = body(eoqScenarioId, "client-1", eoqParams(), -1L);
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negSeed)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("seed")));
    }

    @Test
    @DisplayName("T041: clientId 白名单 — 查询/取消接口同样拒绝非法格式（400 而非 403/404）")
    void clientIdWhitelistOnQuery() throws Exception {
        long runId = createRun(eoqScenarioId, "client-1", eoqParams());
        for (String path : List.of("/api/runs/{id}", "/api/runs/{id}/result")) {
            mockMvc.perform(get(path, runId).param("clientId", "bad client!")).andExpect(status().isBadRequest());
        }
        mockMvc.perform(delete("/api/runs/{id}", runId).param("clientId", "bad client!"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/runs/{id}", runId).param("clientId", "ok"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("T041: params 结构守卫 — 数量超限/超长字符串返回 400")
    void paramsShapeGuardRejected() throws Exception {
        Map<String, Object> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            tooMany.put("key_" + i, i);
        }
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(eoqScenarioId, "client-1", tooMany, 42))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("参数数量超限")));

        Map<String, Object> longStr = eoqParams();
        longStr.put("annual_demand", "x".repeat(300));
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(eoqScenarioId, "client-1", longStr, 42))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("字符串")));
    }

    @Test
    @DisplayName("T041: 请求体非 JSON / 路径参数类型错误 返回 400（而非 500）")
    void malformedRequestIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{ 这不是 JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(get("/api/runs/abc").param("clientId", "client-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(get("/api/runs/{id}", 1L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("T041: 未注册引擎返回 500 且不泄露内部细节（无异常类名/堆栈）")
    void unregisteredEngineDoesNotLeak() throws Exception {
        long orphanId = insertScenario("CH2-X", "孤儿场景", "no-such-engine");
        mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(orphanId, "client-1", eoqParams(), 42))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后重试"));
    }

    // ---- 夹具与工具 ----

    private long insertScenario(String moduleId, String name, String engineKey) throws Exception {
        Scenario scenario = new Scenario();
        scenario.setChapterId(chapterMapper.selectOne(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getCode, "CH2")).getId());
        scenario.setModuleId(moduleId);
        scenario.setName(name);
        scenario.setEngineKey(engineKey);
        scenario.setDifficulty("intro");
        scenario.setClassHours(2);
        scenario.setIsRolePlay(false);
        scenario.setConcept("概念");
        scenario.setDescription("流程");
        scenario.setDeps("[]");
        scenario.setParams(objectMapper.writeValueAsString(List.of(
                Map.of("key", "annual_demand", "label", "年需求量", "type", "int", "min", 100, "max", 100000, "default", 10000),
                Map.of("key", "order_cost", "label", "订货成本", "type", "float", "min", 10, "max", 5000, "default", 100),
                Map.of("key", "holding_cost", "label", "持有成本", "type", "float", "min", 1, "max", 500, "default", 2))));
        scenario.setOutputs("[]");
        scenario.setConstraints("[]");
        scenarioMapper.insert(scenario);
        return scenario.getId();
    }

    private static Map<String, Object> eoqParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("annual_demand", 10000);
        p.put("order_cost", 100.0);
        p.put("holding_cost", 2.0);
        p.put("lead_time", 7.0);
        p.put("daily_demand", 27.4);
        p.put("order_qty", 0);
        return p;
    }

    private static Map<String, Object> body(Long scenarioId, String clientId, Map<String, Object> params, long seed) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("scenarioId", scenarioId);
        b.put("clientId", clientId);
        b.put("params", params);
        b.put("seed", seed);
        return b;
    }

    private long createRun(long scenarioId, String clientId, Map<String, Object> params) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(scenarioId, clientId, params, 42))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").path("runId").asLong();
    }

    private JsonNode fetchStatus(long runId, String clientId) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/runs/{id}", runId).param("clientId", clientId)).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200, res.getResponse().getStatus(), "状态轮询应 200");
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data");
    }

    private void awaitStatus(long runId, String clientId, String expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(fetchStatus(runId, clientId).path("status").asText())) {
                return;
            }
            Thread.sleep(20);
        }
        fail("运行未在 " + timeoutMs + "ms 内进入 " + expected + "（当前: " + fetchStatus(runId, clientId).path("status") + "）");
    }

    private void awaitStepCount(long runId, String clientId, int minSteps, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (fetchStatus(runId, clientId).path("stepCount").asInt() >= minSteps) {
                return;
            }
            Thread.sleep(10);
        }
        fail("步骤数未在 " + timeoutMs + "ms 内达到 " + minSteps);
    }

    /** 测试专用慢速执行器：200 步 × 5ms，供确定性取消测试（不参与主场景注册）。 */
    @TestConfiguration
    static class SlowExecutorConfig {

        @Bean
        ScenarioExecutor slowExecutor() {
            return new ScenarioExecutor() {
                @Override
                public String engineKey() {
                    return "slow";
                }

                @Override
                public List<String> validate(Map<String, Object> params) {
                    return List.of();
                }

                @Override
                public Integer describeSteps(Map<String, Object> params) {
                    return 200;
                }

                @Override
                public void run(Map<String, Object> params, SimContext ctx) {
                    for (int i = 1; i <= 200 && !ctx.isCancelled(); i++) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("step", i);
                        ctx.step("慢速步骤 " + i, data);
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            };
        }
    }
}
