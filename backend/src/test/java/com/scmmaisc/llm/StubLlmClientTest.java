package com.scmmaisc.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StubLlmClient 角色差异化测试（T028，US2）：stub 模式下四角色发言含各自职责关键词
 * （柳=业务/案例、霍=模型/公式、景=计算/跨章、钟=提问/确认），SC-003 确定性通过；
 * 结论轮返回合法 JSON（12 要素键，SC-004）；插话注入时带回应前缀（US3）。
 * 消息格式对齐 PromptBuilder 约定（人设头含全角角色编码 + 场景名称/数据摘要行）。
 */
class StubLlmClientTest {

    private final StubLlmClient stub = new StubLlmClient();

    private String completeAs(String role, int round, String userExtra) {
        String system = "# 角色（" + role + "）职责描述\n"
                + "场景名称：EOQ经济订货批量\n"
                + "核心概念：库存管理\n"
                + "数据摘要：annual_demand=10000; q_star=1000件(EOQ最优订货量)";
        String user = "【第" + round + "轮：现象解读】你是某某，围绕场景「EOQ经济订货批量」发言。"
                + userExtra;
        return stub.complete(List.of(LlmMessage.system(system), LlmMessage.user(user)), 0.7);
    }

    @Test
    @DisplayName("柳经理发言：业务语言/案例对标/现实差距关键词（US2 职责画像）")
    void liuSpeaksBusinessLanguage() {
        String content = completeAs("LIU", 1, "");
        assertTrue(content.contains("业务语言"), "柳经理须含业务语言翻译");
        assertTrue(content.contains("对标"), "柳经理须含案例对标");
        assertTrue(content.contains("差距"), "柳经理须指出理想化差距");
    }

    @Test
    @DisplayName("霍教授发言：理论模型/推导/教材章节/假设边界关键词")
    void huoSpeaksTheory() {
        String content = completeAs("HUO", 1, "");
        assertTrue(content.contains("理论模型"), "霍教授须含理论模型");
        assertTrue(content.contains("推导"), "霍教授须含推导逻辑");
        assertTrue(content.contains("教材章节"), "霍教授须含教材章节");
        assertTrue(content.contains("假设边界"), "霍教授须含假设边界");
    }

    @Test
    @DisplayName("景同学发言：计算复盘/敏感性验证/跨章引用关键词")
    void jingSpeaksCalculation() {
        String content = completeAs("JING", 1, "");
        assertTrue(content.contains("计算复盘"), "景同学须含计算复盘");
        assertTrue(content.contains("敏感性"), "景同学须含敏感性验证");
        assertTrue(content.contains("跨章"), "景同学须含跨章引用");
    }

    @Test
    @DisplayName("钟同学发言：提问/结论确认关键词")
    void zhongSpeaksQuestions() {
        String content = completeAs("ZHONG", 1, "");
        assertTrue(content.contains("问题"), "钟同学须含提问");
        assertTrue(content.contains("确认"), "钟同学须含结论确认");
    }

    @Test
    @DisplayName("发言引用数据摘要：参数/指标值与注入一致（SC-005 零虚构）")
    void utteranceReferencesSummaryData() {
        String content = completeAs("JING", 2, "");
        assertTrue(content.contains("annual_demand=10000"), "发言须引用注入的参数值");
        assertTrue(content.contains("q_star=1000"), "发言须引用注入的指标值");
    }

    @Test
    @DisplayName("插话注入：带回应学生问题前缀（US3 预埋）")
    void questionPrefixWhenPendingQuestion() {
        String content = completeAs("JING", 3, "本轮需回应学生问题：奶茶店能用EOQ吗？（同辈角色优先回应）");
        assertTrue(content.contains("关于你提出的问题"), "待回应问题时须先回应学生问题");
    }

    @Test
    @DisplayName("结论轮：返回合法 JSON，三段 × 4 要素键齐全（SC-004）")
    void conclusionJsonHasTwelveKeys() {
        String system = "# 角色（HUO）职责描述\n场景名称：EOQ经济订货批量\n数据摘要：annual_demand=10000";
        String content = stub.complete(
                List.of(LlmMessage.system(system), LlmMessage.user("【结论生成】请输出三段式结论 JSON")), 0.7);

        for (String key : List.of("coreModel", "derivation", "assumptions", "knowledgeLocation",
                "paramBusiness", "caseBenchmark", "simRealityGap", "suggestions",
                "industry", "academic", "studentAdvice", "voteItem")) {
            assertTrue(content.contains(key), "结论须含要素键 " + key);
        }
    }
}
