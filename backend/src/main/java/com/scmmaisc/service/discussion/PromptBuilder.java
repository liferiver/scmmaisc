package com.scmmaisc.service.discussion;

import com.scmmaisc.engine.OutputValue;
import com.scmmaisc.llm.LlmMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提示词组装（research.md §6/§7）：system = 角色人设（resources/prompts/personas/*.md，
 * 文案与代码分离）+ 场景上下文 + 数据摘要；user = 轮次指令 + 学生插话注入。
 * 数据摘要仅含指标数值与文字（图表不进入 LLM 输入，澄清 Q3），
 * 格式约定与 StubLlmClient 的提取正则配套（确定性测试依赖）。
 */
@Component
public class PromptBuilder {

    /** 五轮固定流程（FR-002）。 */
    public static final String[] ROUND_TITLES = {"现象解读", "深度剖析", "跨章连接", "前沿延伸", "三维收敛"};

    /** 每轮固定发言顺序：景同学开场（SC-002 首条快速呈现），霍/柳随后，钟收尾确认。 */
    public static final String[] ROUND_ORDER = {"JING", "HUO", "LIU", "ZHONG"};

    private static final Map<String, String> ROLE_NAMES = Map.of(
            "LIU", "柳经理", "HUO", "霍教授", "JING", "景同学", "ZHONG", "钟同学");

    /** 历史上下文上限：最近 3 轮发言（内存有界，宪法 IV）。 */
    private static final int HISTORY_MAX_UTTERANCES = 12;

    /** 前置发言（供编排器与导出复用）。 */
    public record UtteranceView(String agentRole, String content) {
    }

    /** 场景上下文（含运行快照摘要所需的最小信息与知识图谱配置，US5）。 */
    public record Context(String scenarioName, String concept, String chapterSection,
                          Map<String, Object> params, List<OutputValue> outputs,
                          List<String> prevKnowledge, List<String> nextExtension,
                          List<String> discussionStarters, List<String> caseLibrary,
                          List<String> theoryLibrary) {
        /** 兼容无配置场景（知识图谱字段为空，Prompt 走通用引导模板，零虚构 US5-AC3）。 */
        public Context(String scenarioName, String concept, String chapterSection,
                       Map<String, Object> params, List<OutputValue> outputs) {
            this(scenarioName, concept, chapterSection, params, outputs,
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /**
     * 组装一条发言的完整消息序列。
     *
     * @param personaFile    角色人设文件（classpath 相对路径）
     * @param ctx            场景与运行快照上下文
     * @param round          当前轮次 1..5
     * @param role           角色编码 LIU/HUO/JING/ZHONG
     * @param history        前序发言（assistant 上下文，最多 HISTORY_MAX_UTTERANCES 条）
     * @param pendingQuestion 待回应学生问题（无则 null；US3 注入）
     */
    public List<LlmMessage> build(String personaFile, Context ctx, int round, String role,
                                  List<UtteranceView> history, String pendingQuestion) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt(personaFile, ctx)));
        int from = Math.max(0, history.size() - HISTORY_MAX_UTTERANCES);
        for (UtteranceView u : history.subList(from, history.size())) {
            messages.add(LlmMessage.assistant(u.content()));
        }
        messages.add(LlmMessage.user(userPrompt(ctx, round, role, pendingQuestion)));
        return messages;
    }

    /** 结论生成轮的消息序列（【结论生成】指令，StubLlmClient 据此返回 JSON）。 */
    public List<LlmMessage> buildConclusion(String personaFile, Context ctx, List<UtteranceView> history) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt(personaFile, ctx)));
        int from = Math.max(0, history.size() - HISTORY_MAX_UTTERANCES);
        for (UtteranceView u : history.subList(from, history.size())) {
            messages.add(LlmMessage.assistant(u.content()));
        }
        messages.add(LlmMessage.user("【结论生成】请基于本次运行结果与以上全部讨论，输出三段式结论 JSON："
                + "理论结论（coreModel/derivation/assumptions/knowledgeLocation）、"
                + "实操结论（paramBusiness/caseBenchmark/simRealityGap/suggestions）、"
                + "前沿结论（industry/academic/studentAdvice/voteItem，voteItem 为钟同学兴趣投票）。"
                + "引用数据必须与数据摘要一致，不得虚构。"));
        return messages;
    }

    /** system prompt：人设 + 场景名称/概念/章节/前序后续知识图谱 + 数据摘要（约定格式，Stub 提取依赖）。 */
    public String systemPrompt(String personaFile, Context ctx) {
        String persona = loadPersona(personaFile);
        StringBuilder sb = new StringBuilder(persona)
                .append("\n\n场景名称：").append(ctx.scenarioName())
                .append("\n核心概念：").append(ctx.concept() == null ? "未配置" : ctx.concept())
                .append("\n章节定位：")
                .append(ctx.chapterSection() == null || ctx.chapterSection().isBlank()
                        ? "见教材对应章节" : ctx.chapterSection());
        // US5：知识图谱元数据（仅给定内容可引用，零虚构 SC-010）
        if (!ctx.prevKnowledge().isEmpty()) {
            sb.append("\n前序知识：").append(String.join("、", ctx.prevKnowledge()));
        }
        if (!ctx.nextExtension().isEmpty()) {
            sb.append("\n后续延伸：").append(String.join("、", ctx.nextExtension()));
        }
        // US5：人工覆盖差异化内容（案例/理论库索引，未配置时为空不虚构）
        if (!ctx.caseLibrary().isEmpty()) {
            sb.append("\n案例库索引：").append(String.join("、", ctx.caseLibrary()));
        }
        if (!ctx.theoryLibrary().isEmpty()) {
            sb.append("\n理论库索引：").append(String.join("、", ctx.theoryLibrary()));
        }
        sb.append("\n数据摘要：").append(summary(ctx.params(), ctx.outputs()));
        return sb.toString();
    }

    /** 每轮 user 指令（含 US3 插话注入、US5 切入点与链路元数据）。 */
    public String userPrompt(Context ctx, int round, String role, String pendingQuestion) {
        StringBuilder sb = new StringBuilder();
        sb.append("【第").append(round).append("轮：").append(ROUND_TITLES[round - 1]).append("】")
                .append("你是").append(ROLE_NAMES.getOrDefault(role, role)).append("，")
                .append("围绕场景「").append(ctx.scenarioName()).append("」的本次运行结果发言，");
        switch (round) {
            case 1 -> {
                sb.append("先解读你观察到的现象与关键指标，用你的角色语言说明它意味着什么；");
                // US5：注入典型讨论切入点（未配置时为空，不虚构）
                if (!ctx.discussionStarters().isEmpty()) {
                    sb.append("\n典型讨论切入点（可选其一展开）：")
                            .append(String.join("；", ctx.discussionStarters()));
                }
            }
            case 2 -> sb.append("对第 1 轮的现象做深度剖析：模型机制、参数作用、因果解释；");
            case 3 -> {
                sb.append("勾连其他章节的前序知识与后续延伸（仅限给定元数据，不得虚构章节引用）");
                if (ctx.prevKnowledge().isEmpty() && ctx.nextExtension().isEmpty()) {
                    // 无配置场景：通用引导模板，零虚构（US5-AC3）
                    sb.append("；本场景未提供链路元数据，仅作概括性关联，不得引用具体章节名");
                } else {
                    if (!ctx.prevKnowledge().isEmpty()) {
                        sb.append("；前序知识：").append(String.join("、", ctx.prevKnowledge()));
                    }
                    if (!ctx.nextExtension().isEmpty()) {
                        sb.append("；后续延伸：").append(String.join("、", ctx.nextExtension()));
                    }
                }
            }
            case 4 -> sb.append("从你的视角延伸前沿方向（产业/学术/学生关注，课程外内容须标注「前沿延伸，非考试范围」）；");
            default -> sb.append("收敛三维视角，形成本轮总结性发言，并回应尚未解决的问题；");
        }
        if (pendingQuestion != null && !pendingQuestion.isBlank()) {
            sb.append("\n本轮需回应学生问题：").append(pendingQuestion).append("（同辈角色优先回应，无法解答转交专家角色）");
        }
        sb.append("\n引用数据必须与数据摘要一致，不得虚构或串用其他运行的数据。单条发言 150-300 字。");
        return sb.toString();
    }

    /** 结构化文字摘要（Q3：数值与文字，不含图表）；约定 "k=v; k=v" 格式。 */
    public String summary(Map<String, Object> params, List<OutputValue> outputs) {
        List<String> parts = new ArrayList<>();
        if (params != null) {
            for (Map.Entry<String, Object> e : new LinkedHashMap<>(params).entrySet()) {
                parts.add(e.getKey() + "=" + compact(e.getValue()));
            }
        }
        if (outputs != null) {
            for (OutputValue o : outputs) {
                if (o.value() == null) {
                    continue;
                }
                String label = o.label() == null || o.label().isBlank() ? o.key() : o.label();
                parts.add(o.key() + "=" + compact(o.value())
                        + (o.unit() == null || o.unit().isBlank() ? "" : o.unit())
                        + "(" + label + ")");
            }
        }
        return String.join("; ", parts);
    }

    /** 复杂结构（数组/对象）压缩为结构化摘要，如 "10 家供应商 × 6 维度得分"。 */
    private static String compact(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size() + " 项结构化数据";
        }
        if (value instanceof List<?> list) {
            return list.size() + " 项数据";
        }
        if (value instanceof Double d) {
            long l = d.longValue();
            return d.equals((double) l) ? String.valueOf(l) : String.valueOf(d);
        }
        return String.valueOf(value);
    }

    private String loadPersona(String personaFile) {
        try {
            return new String(new ClassPathResource(personaFile).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("角色人设文件加载失败: " + personaFile, e);
        }
    }
}
