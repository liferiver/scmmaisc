package com.scmmaisc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.entity.Chapter;
import com.scmmaisc.entity.Scenario;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * US1 契约测试（T014）：C1 章节列表 / C2 场景列表 / C3 场景详情 / 404。
 * 夹具数据由测试自建（不依赖主资源 scenarios/*.json），保证与装载器解耦。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScenarioCatalogControllerTest {

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

    @BeforeEach
    void setUp() throws Exception {
        // 依赖顺序清理（宪法 II：用例独立、不依赖执行顺序）：先删日志/运行，再删场景/章节
        logMapper.delete(null);
        runMapper.delete(null);
        scenarioMapper.delete(null);
        chapterMapper.delete(null);
        insertFixture();
    }

    @Test
    @DisplayName("C1: GET /api/chapters 返回 11 章，含各章场景数聚合")
    void chaptersReturnsAll11() throws Exception {
        mockMvc.perform(get("/api/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(11))
                .andExpect(jsonPath("$.data[0].code").value("CH1"))
                .andExpect(jsonPath("$.data[0].name").value("概论"))
                .andExpect(jsonPath("$.data[1].scenarioCount").value(1))
                .andExpect(jsonPath("$.data[9].code").value("CH10"));
    }

    @Test
    @DisplayName("C2: GET /api/scenarios?chapterId= 返回场景概要")
    void scenariosByChapter() throws Exception {
        long chapter2Id = chapterId("CH2");
        mockMvc.perform(get("/api/scenarios").param("chapterId", String.valueOf(chapter2Id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].moduleId").value("CH2-003"))
                .andExpect(jsonPath("$.data[0].name").value("EOQ 经济订货批量"))
                .andExpect(jsonPath("$.data[0].difficulty").value("intro"))
                .andExpect(jsonPath("$.data[0].isRolePlay").value(false));
    }

    @Test
    @DisplayName("C3: GET /api/scenarios/CH2-003 返回完整定义")
    void scenarioDetail() throws Exception {
        mockMvc.perform(get("/api/scenarios/CH2-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.moduleId").value("CH2-003"))
                .andExpect(jsonPath("$.data.engineKey").value("eoq"))
                .andExpect(jsonPath("$.data.concept").isNotEmpty())
                .andExpect(jsonPath("$.data.params.length()").value(3))
                .andExpect(jsonPath("$.data.params[0].key").value("annual_demand"))
                .andExpect(jsonPath("$.data.params[0].label").value("年需求量"))
                .andExpect(jsonPath("$.data.params[0].min").value(1))
                .andExpect(jsonPath("$.data.outputs.length()").value(3))
                .andExpect(jsonPath("$.data.outputs[0].type").value("scalar"))
                .andExpect(jsonPath("$.data.constraints[0].message").isNotEmpty());
    }

    @Test
    @DisplayName("C3: 不存在的 moduleId 返回 404 + 40401")
    void scenarioNotFound() throws Exception {
        mockMvc.perform(get("/api/scenarios/CH9-999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    // ---- 夹具 ----

    private long chapterId(String code) {
        return chapterMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getCode, code)).getId();
    }

    private void insertFixture() throws Exception {
        String[] codes = {"CH1", "CH2", "CH3", "CH4", "CH5", "CH6", "CH7", "CH8", "CH9", "CH10", "CH11"};
        String[] names = {"概论", "物流系统控制", "物流技术与信息系统", "电子商务环境下的物流系统",
                "跨境物流管理", "供应链管理", "供应链设计与构建", "供应链协同", "供应链金融",
                "全球供应链", "现代物流与供应链发展"};
        for (int i = 0; i < codes.length; i++) {
            Chapter chapter = new Chapter();
            chapter.setCode(codes[i]);
            chapter.setName(names[i]);
            chapter.setSortNo(i + 1);
            chapterMapper.insert(chapter);
        }
        insertScenario("CH1", "CH1-002", "物流 7R 服务目标履约", "seven-r", "intro", 2, false,
                List.of(), params7r(), outputs7r(), List.of());
        insertScenario("CH2", "CH2-003", "EOQ 经济订货批量", "eoq", "intro", 2, false,
                List.of(), paramsEoq(), outputsEoq(),
                List.of(Map.of("name", "qty_gt_0", "expression", "q > 0", "message", "订货批量必须为正数")));
        insertScenario("CH8", "CH8-001", "啤酒游戏——牛鞭效应", "beer-game", "basic", 3, true,
                List.of(), List.of(), List.of(), List.of());
    }

    private void insertScenario(String chapterCode, String moduleId, String name, String engineKey,
                                String difficulty, int classHours, boolean rolePlay,
                                List<String> deps, List<Map<String, Object>> params,
                                List<Map<String, Object>> outputs,
                                List<Map<String, Object>> constraints) throws Exception {
        long chapterId = chapterId(chapterCode);
        Scenario scenario = new Scenario();
        scenario.setChapterId(chapterId);
        scenario.setModuleId(moduleId);
        scenario.setName(name);
        scenario.setEngineKey(engineKey);
        scenario.setDifficulty(difficulty);
        scenario.setClassHours(classHours);
        scenario.setIsRolePlay(rolePlay);
        scenario.setConcept("核心概念：" + name);
        scenario.setDescription("流程描述：" + name);
        scenario.setDeps(objectMapper.writeValueAsString(deps));
        scenario.setParams(objectMapper.writeValueAsString(params));
        scenario.setOutputs(objectMapper.writeValueAsString(outputs));
        scenario.setConstraints(objectMapper.writeValueAsString(constraints));
        scenarioMapper.insert(scenario);
    }

    private static List<Map<String, Object>> params7r() {
        return List.of(
                Map.of("key", "order_cycle", "label", "订单周期", "type", "int", "min", 1, "max", 30, "default", 7),
                Map.of("key", "on_time_rate", "label", "准时率目标", "type", "float", "min", 0.0, "max", 1.0, "default", 0.95));
    }

    private static List<Map<String, Object>> paramsEoq() {
        return List.of(
                Map.of("key", "annual_demand", "label", "年需求量", "type", "int", "unit", "件",
                        "min", 1, "max", 1000000, "default", 10000, "description", "全年总需求量 D"),
                Map.of("key", "order_cost", "label", "单次订货成本", "type", "float", "unit", "元/次",
                        "min", 1, "max", 100000, "default", 100, "description", "每次订货的固定成本 S"),
                Map.of("key", "holding_cost", "label", "单位年持有成本", "type", "float", "unit", "元/件·年",
                        "min", 0.01, "max", 10000, "default", 2, "description", "单件库存年持有成本 H"));
    }

    private static List<Map<String, Object>> outputsEoq() {
        return List.of(
                Map.of("key", "eoq", "label", "经济订货批量", "type", "scalar", "unit", "件"),
                Map.of("key", "total_cost", "label", "年总成本", "type", "scalar", "unit", "元"),
                Map.of("key", "inventory_series", "label", "库存变化曲线", "type", "series"));
    }

    private static List<Map<String, Object>> outputs7r() {
        return List.of(
                Map.of("key", "fulfill_rate", "label", "履约率", "type", "scalar"),
                Map.of("key", "cost", "label", "总成本", "type", "scalar", "unit", "元"));
    }
}
