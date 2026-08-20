package com.scmmaisc.llm;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stub 语言模型（research.md §10，宪法 II）：{@code llm.stub=true} / {@code LLM_STUB=true} 时启用。
 * 按（角色, 轮次, 场景）从消息中提取特征，返回确定性预设文本：
 * 发言含角色职责关键词（SC-003 四角色齐全）、结论返回合法 JSON（SC-004 12 要素），
 * 且文本引用 PromptBuilder 注入的数据摘要（SC-005 数据一致、零虚构）。
 */
public class StubLlmClient implements LlmClient {

    /** 角色编码须显式匹配（LIU/HUO 为 3 字母、ZHONG 为 5 字母，长度不定；T028 修复）。 */
    private static final Pattern ROLE_PATTERN = Pattern.compile("[（(](LIU|HUO|JING|ZHONG)[）)]");
    private static final Pattern SCENARIO_PATTERN = Pattern.compile("场景名称：([^\\n]+)");
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("数据摘要：([^\\n]+)");
    private static final Pattern ROUND_PATTERN = Pattern.compile("【第(\\d)轮");

    @Override
    public String complete(List<LlmMessage> messages, double temperature) {
        String system = messages.stream()
                .filter(m -> "system".equals(m.role()))
                .map(LlmMessage::content)
                .reduce("", (a, b) -> a + "\n" + b);
        String lastUser = messages.stream()
                .filter(m -> "user".equals(m.role()))
                .map(LlmMessage::content)
                .reduce((a, b) -> b)
                .orElse("");

        String role = extract(ROLE_PATTERN, system, "JING");
        String scenario = extract(SCENARIO_PATTERN, system, "本场景");
        String summary = extract(SUMMARY_PATTERN, system, "");

        if (lastUser.contains("【结论生成】")) {
            return conclusionJson(scenario, summary);
        }
        int round = parseRound(lastUser);
        boolean hasQuestion = lastUser.contains("学生问题") || system.contains("需回应学生问题");
        return utterance(role, round, scenario, summary, hasQuestion);
    }

    /** 角色差异化发言模板（职责关键词满足 US2 校验）。 */
    private String utterance(String role, int round, String scenario, String summary, boolean hasQuestion) {
        String questionPrefix = hasQuestion ? "关于你提出的问题，我先回应一下：这个问题确实值得展开……\n\n" : "";
        String data = summary.isBlank() ? "" : "（本次运行数据：" + summary + "）";
        return switch (role) {
            case "LIU" -> questionPrefix + "从企业运营的角度看，" + scenario + data
                    + "，这个结果直接对应现实中的订货与库存决策；"
                    + "我会把它翻译成业务语言，并结合行业常见做法做对标，指出仿真理想化假设与现实的差距。";
            case "HUO" -> questionPrefix + "从理论模型的视角看，" + scenario + "的本质是确定型库存模型的优化问题，"
                    + "其推导逻辑与教材章节对应；" + data
                    + "需要提醒的是模型的假设边界——忽略缺货成本等现实因素，经典理论与仿真实现之间因此存在差异。";
            case "JING" -> questionPrefix + "我先做结构化计算复盘：" + data
                    + "，验证参数敏感性后可以勾连其他章节的知识点——"
                    + "这个结论与供应链协同链路（如推拉策略、数字孪生）存在关联，我给出跨章引用。";
            default -> questionPrefix + "我有一个问题想向各位确认：这个结论用生活直觉怎么理解？"
                    + scenario + "的结论我们看得懂吗？请用更简单的语言再解释一遍，确保不遗漏关键假设。";
        };
    }

    /** 结论 JSON：三段 × 4 要素（键与 ConclusionParser 校验一致）。 */
    private String conclusionJson(String scenario, String summary) {
        String data = summary.isBlank() ? "本次运行参数与指标" : summary;
        return """
                {
                  "theory": {
                    "coreModel": "核心模型：针对 %s 的库存优化模型，推导自经济订货批量理论，知识图谱定位见教材章节",
                    "derivation": "推导要点：基于 %s，通过总成本最小化得到最优订货决策，边际权衡是关键",
                    "assumptions": "假设边界：需求确定、忽略缺货成本与批量折扣，仿真实现与经典理论的差异由此而来",
                    "knowledgeLocation": "知识图谱定位：第 2 章库存管理（EOQ 模型），与第 7 章推拉策略、第 11 章数字孪生关联"
                  },
                  "practice": {
                    "paramBusiness": "参数业务翻译：%s 中的参数对应现实订货周期、单次订货成本与持有成本",
                    "caseBenchmark": "真实案例对标：可对标零售/制造企业常用库存管理实践（行业通用做法，非虚构引用）",
                    "simRealityGap": "仿真与现实差距：仿真忽略需求波动与供应商不确定性，现实决策需叠加安全库存",
                    "suggestions": "落地建议：先按模型计算结果设置订货批量，再结合实际服务水平逐步调整"
                  },
                  "frontier": {
                    "industry": "产业前沿：智能补货与需求预测算法正在改变传统订货决策（前沿延伸，非考试范围）",
                    "academic": "学术前沿：报童模型与随机库存理论是该方向的经典延伸（前沿延伸，非考试范围）",
                    "studentAdvice": "学生关注建议：建议对照课程第 7 章推拉策略复习，理解库存决策在供应链中的位置",
                    "voteItem": "兴趣投票：钟同学最想深入『需求不确定下的库存决策』，因其最贴近日常购物体验"
                  }
                }
                """.formatted(scenario, data, data);
    }

    private static int parseRound(String lastUser) {
        Matcher m = ROUND_PATTERN.matcher(lastUser);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static String extract(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : fallback;
    }
}
