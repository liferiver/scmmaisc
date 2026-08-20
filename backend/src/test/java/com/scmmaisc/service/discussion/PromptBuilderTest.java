package com.scmmaisc.service.discussion;

import com.scmmaisc.engine.OutputValue;
import com.scmmaisc.llm.LlmMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PromptBuilder 测试（T025，US2）：角色人设注入（四角色 system prompt 差异化）、
 * 场景上下文（名称/概念/章节/数据摘要）格式、轮次指令、数据摘要不含图表（仅数值与文字）、
 * 插话注入（US3 预埋）。纯单元测试（无 Spring 上下文）。
 */
class PromptBuilderTest {

    private PromptBuilder builder;

    private static final String LIU_PERSONA = "prompts/personas/liu.md";
    private static final String HUO_PERSONA = "prompts/personas/huo.md";
    private static final String JING_PERSONA = "prompts/personas/jing.md";
    private static final String ZHONG_PERSONA = "prompts/personas/zhong.md";

    @BeforeEach
    void setUp() {
        builder = new PromptBuilder();
    }

    private PromptBuilder.Context ctx() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("annual_demand", 10000);
        params.put("order_cost", 100);
        params.put("holding_cost", 2.0);
        return new PromptBuilder.Context("EOQ经济订货批量", "库存管理,经济订货批量", "第2章 库存管理",
                params, List.of(new OutputValue("q_star", "EOQ最优订货量", "scalar", 1000, "件")));
    }

    /** 带知识图谱配置的上下文（US5：人工覆盖差异化内容）。 */
    private PromptBuilder.Context profileCtx() {
        return new PromptBuilder.Context("EOQ经济订货批量", "库存管理,经济订货批量", "第二章 物流系统控制",
                Map.of("annual_demand", 10000), List.of(),
                List.of("需求预测"), List.of("啤酒游戏——牛鞭效应", "随机需求库存(s,Q)策略仿真"),
                List.of("比较 Q* 与实际订货量的成本差异"),
                List.of("教材案例：制造业采购批量决策"), List.of("EOQ 模型推导与假设边界"));
    }

    @Test
    @DisplayName("四角色 system prompt 差异化：人设关键词各自注入（US2 职责画像）")
    void personaInjectionDiffersPerRole() {
        String liu = builder.systemPrompt(LIU_PERSONA, ctx());
        String huo = builder.systemPrompt(HUO_PERSONA, ctx());
        String jing = builder.systemPrompt(JING_PERSONA, ctx());
        String zhong = builder.systemPrompt(ZHONG_PERSONA, ctx());

        // 柳经理：业务语言/案例对标/现实差距
        assertTrue(liu.contains("柳经理"));
        assertTrue(liu.contains("业务语言"));
        assertTrue(liu.contains("案例"));
        assertTrue(liu.contains("差距"));
        // 霍教授：理论模型/推导/教材章节/假设边界
        assertTrue(huo.contains("霍教授"));
        assertTrue(huo.contains("理论"));
        assertTrue(huo.contains("推导"));
        assertTrue(huo.contains("章节"));
        assertTrue(huo.contains("适用边界"));
        // 景同学：计算复盘/敏感性/跨章
        assertTrue(jing.contains("景同学"));
        assertTrue(jing.contains("计算复盘"));
        assertTrue(jing.contains("敏感性"));
        assertTrue(jing.contains("跨章"));
        // 钟同学：生活直觉提问/结论确认
        assertTrue(zhong.contains("钟同学"));
        assertTrue(zhong.contains("提问"));
        assertTrue(zhong.contains("确认"));
        // 伦理约束（FR-008）：不贬低学生提问
        assertTrue(liu.contains("不贬低"));
        assertTrue(huo.contains("不贬低"));
        assertTrue(jing.contains("不贬低"));
        assertTrue(zhong.contains("不贬低"));
    }

    @Test
    @DisplayName("场景上下文注入：名称/概念/章节/数据摘要四段齐全且格式约定一致")
    void scenarioContextInjected() {
        String system = builder.systemPrompt(LIU_PERSONA, ctx());

        assertTrue(system.contains("场景名称：EOQ经济订货批量"));
        assertTrue(system.contains("核心概念：库存管理,经济订货批量"));
        assertTrue(system.contains("章节定位：第2章 库存管理"));
        assertTrue(system.contains("数据摘要："));
    }

    @Test
    @DisplayName("数据摘要：参数/指标数值化文字，复杂结构与图表数据压缩不进入（澄清 Q3）")
    void summaryIsTextualNoCharts() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("annual_demand", 10000);
        params.put("risk_matrix", List.of(List.of(1, 2), List.of(3, 4))); // 矩阵 → 压缩
        params.put("supplier_scores", Map.of("s1", 0.9, "s2", 0.7)); // 对象 → 压缩
        String summary = builder.summary(params, List.of(
                new OutputValue("q_star", "EOQ最优订货量", "scalar", 1000, "件"),
                new OutputValue("routes", "路径矩阵", "matrix", List.of("A", "B"), null),
                new OutputValue("demand_series", "需求时序", "timeseries", List.of(1, 2, 3), "件")));

        assertTrue(summary.contains("annual_demand=10000"));
        assertTrue(summary.contains("risk_matrix=2 项数据"));
        assertTrue(summary.contains("supplier_scores=2 项结构化数据"));
        assertTrue(summary.contains("q_star=1000件(EOQ最优订货量)"));
        assertTrue(summary.contains("routes=2 项数据(路径矩阵)"));
        assertTrue(summary.contains("demand_series=3 项数据件(需求时序)"));
        // 不含原始矩阵/时序数值（图表数据不进入 LLM 输入）
        assertFalse(summary.contains("[[1, 2"));
        assertFalse(summary.contains("s1"));
    }

    @Test
    @DisplayName("五轮 user 指令：轮次标题与角色名正确（FR-002）")
    void roundInstructions() {
        String[] titles = {"现象解读", "深度剖析", "跨章连接", "前沿延伸", "三维收敛"};
        for (int round = 1; round <= 5; round++) {
            String prompt = builder.userPrompt(ctx(), round, "HUO", null);
            assertTrue(prompt.contains("【第" + round + "轮：" + titles[round - 1] + "】"), "第 " + round + " 轮标题");
            assertTrue(prompt.contains("你是霍教授"), "第 " + round + " 轮角色名");
            assertTrue(prompt.contains("不得虚构"), "第 " + round + " 轮反幻觉约束（FR-005）");
        }
    }

    @Test
    @DisplayName("插话注入：待回应问题进入 user 消息头部且同辈优先（US3 预埋）")
    void pendingQuestionInjected() {
        String prompt = builder.userPrompt(ctx(), 3, "JING", "奶茶店能用EOQ吗？");

        assertTrue(prompt.contains("本轮需回应学生问题：奶茶店能用EOQ吗？"));
        assertTrue(prompt.contains("同辈角色优先回应"));
    }

    @Test
    @DisplayName("US5：R1 注入典型讨论切入点，R3 注入前序知识/后续延伸（仅给定元数据）")
    void profileMetadataInjected() {
        String r1 = builder.userPrompt(profileCtx(), 1, "LIU", null);
        String r3 = builder.userPrompt(profileCtx(), 3, "JING", null);
        String system = builder.systemPrompt(HUO_PERSONA, profileCtx());

        assertTrue(r1.contains("典型讨论切入点（可选其一展开）：比较 Q* 与实际订货量的成本差异"), "R1 切入点注入");
        assertTrue(r3.contains("前序知识：需求预测"), "R3 前序知识注入");
        assertTrue(r3.contains("后续延伸：啤酒游戏——牛鞭效应、随机需求库存(s,Q)策略仿真"), "R3 后续延伸注入");
        assertTrue(system.contains("章节定位：第二章 物流系统控制"), "system 章节定位来自配置");
        assertTrue(system.contains("前序知识：需求预测"), "system 前序知识");
        assertTrue(system.contains("案例库索引：教材案例：制造业采购批量决策"), "人工覆盖案例库索引进入 system");
        assertTrue(system.contains("理论库索引：EOQ 模型推导与假设边界"), "人工覆盖理论库索引进入 system");
    }

    @Test
    @DisplayName("US5-AC3：无配置场景不注入切入点/链路，R3 走通用模板不虚构章节引用")
    void noProfileFallsBackToGeneric() {
        String r1 = builder.userPrompt(ctx(), 1, "LIU", null);
        String r3 = builder.userPrompt(ctx(), 3, "JING", null);
        String system = builder.systemPrompt(HUO_PERSONA, ctx());

        assertFalse(r1.contains("典型讨论切入点"), "无配置不注入切入点");
        assertTrue(r3.contains("本场景未提供链路元数据，仅作概括性关联，不得引用具体章节名"), "通用引导模板");
        assertFalse(system.contains("前序知识："), "无配置 system 不含前序知识行");
        assertFalse(system.contains("后续延伸："), "无配置 system 不含后续延伸行");
        assertFalse(system.contains("案例库索引："), "无配置 system 不含案例库索引");
    }

    @Test
    @DisplayName("消息序列：system → 前序发言(assistant) → user 指令；历史窗口有界（宪法 IV）")
    void messageSequenceAndHistoryWindow() {
        List<PromptBuilder.UtteranceView> history = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            history.add(new PromptBuilder.UtteranceView("HUO", "前序发言" + i));
        }
        List<LlmMessage> messages = builder.build(HUO_PERSONA, ctx(), 2, "HUO", history, null);

        assertTrue(messages.get(0).role().equals("system"), "首条为 system 人设");
        assertTrue(messages.get(messages.size() - 1).role().equals("user"), "末条为 user 指令");
        long assistantCount = messages.stream().filter(m -> m.role().equals("assistant")).count();
        assertTrue(assistantCount <= 12, "历史窗口最多 12 条（HISTORY_MAX_UTTERANCES）");
    }

    @Test
    @DisplayName("结论生成消息序列：含【结论生成】指令与三段 12 要素要求")
    void conclusionPrompt() {
        List<LlmMessage> messages = builder.buildConclusion(HUO_PERSONA, ctx(), List.of());
        String last = messages.get(messages.size() - 1).content();

        assertTrue(last.contains("【结论生成】"));
        assertTrue(last.contains("coreModel"));
        assertTrue(last.contains("paramBusiness"));
        assertTrue(last.contains("voteItem"));
        assertTrue(last.contains("不得虚构"));
    }
}
