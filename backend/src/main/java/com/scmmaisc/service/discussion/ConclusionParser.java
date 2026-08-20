package com.scmmaisc.service.discussion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 三维结论容错解析（research.md §5，SC-004）：剥离 markdown 代码围栏 → 定位首个 JSON 对象 →
 * 结构校验（三段 × 4 要素键齐全）→ 失败重试 1 次（注入重试源）→ 仍失败生成带缺省占位的结论
 * 并附降级说明（conclusion_note，不泄露内部细节）；禁止静默吞错（宪法 I）。
 */
@Component
public class ConclusionParser {

    /** 三段结论的必填要素键（与 StubLlmClient 输出、D3 契约一致）。 */
    public static final List<String> THEORY_KEYS = List.of("coreModel", "derivation", "assumptions", "knowledgeLocation");
    public static final List<String> PRACTICE_KEYS = List.of("paramBusiness", "caseBenchmark", "simRealityGap", "suggestions");
    public static final List<String> FRONTIER_KEYS = List.of("industry", "academic", "studentAdvice", "voteItem");

    private static final String DEGRADED_NOTE = "结论生成出现异常，已提供通用占位内容，请重新发起讨论或联系教师";

    private final ObjectMapper objectMapper;

    public ConclusionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析结果：conclusion 三段要素；note 非空表示降级占位（会话仍 COMPLETED）。 */
    public record ParseResult(ConclusionVO conclusion, String note) {
    }

    /**
     * 解析 LLM 结论文本；结构非法时调用 {@code retrySupplier} 重试一次，仍失败则降级。
     *
     * @param text           LLM 返回文本
     * @param retrySupplier  重试源（通常为再次调用 LLM 结论轮）
     */
    public ParseResult parse(String text, Supplier<String> retrySupplier) {
        if (text == null || text.isBlank()) {
            return degraded("LLM 未返回内容"); // 空输出无需重试，直接降级
        }
        ConclusionVO parsed = tryParse(text);
        if (parsed != null) {
            return new ParseResult(parsed, null);
        }
        String retryText;
        try {
            retryText = retrySupplier.get();
        } catch (RuntimeException e) {
            return degraded("重试调用失败：" + safeHint(e));
        }
        ConclusionVO retried = tryParse(retryText);
        if (retried != null) {
            return new ParseResult(retried, null);
        }
        return degraded("两次解析均未通过结构校验");
    }

    /** 单次尽力解析：剥离围栏 → 首个 JSON 对象 → 结构校验。失败返回 null（不抛异常，交由调用方决策）。 */
    public ConclusionVO tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = stripCodeFences(text);
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripped.substring(start, end + 1));
        } catch (JsonProcessingException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode theory = root.path("theory");
        JsonNode practice = root.path("practice");
        JsonNode frontier = root.path("frontier");
        if (!hasKeys(theory, THEORY_KEYS) || !hasKeys(practice, PRACTICE_KEYS) || !hasKeys(frontier, FRONTIER_KEYS)) {
            return null;
        }
        return new ConclusionVO(
                section(theory, THEORY_KEYS),
                section(practice, PRACTICE_KEYS),
                section(frontier, FRONTIER_KEYS));
    }

    /** 降级占位结论（12 要素齐备，内容为通用提示，SC-004 结构仍通过）。 */
    private ParseResult degraded(String hint) {
        String placeholder = "（本次结论生成未通过校验，请参考以上讨论内容；" + hint + "）";
        SectionVO s = new SectionVO(placeholder, placeholder, placeholder, placeholder);
        return new ParseResult(new ConclusionVO(s, s, s), DEGRADED_NOTE);
    }

    private static String safeHint(RuntimeException e) {
        String msg = e.getMessage();
        return msg == null || msg.isBlank() ? "未知异常" : (msg.length() > 80 ? msg.substring(0, 80) + "…" : msg);
    }

    private static String stripCodeFences(String text) {
        return text.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("(?s)```", "").trim();
    }

    private static boolean hasKeys(JsonNode node, List<String> keys) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return keys.stream().allMatch(k -> node.has(k));
    }

    private static SectionVO section(JsonNode node, List<String> keys) {
        return new SectionVO(keys.stream().map(k -> node.path(k).asText("")).toArray(String[]::new));
    }

    /** 结论 VO（D3 契约：camelCase 键）。 */
    public record ConclusionVO(SectionVO theory, SectionVO practice, SectionVO frontier) {
        public Map<String, String> theoryMap() {
            return sectionMap(THEORY_KEYS, theory);
        }

        public Map<String, String> practiceMap() {
            return sectionMap(PRACTICE_KEYS, practice);
        }

        public Map<String, String> frontierMap() {
            return sectionMap(FRONTIER_KEYS, frontier);
        }

        private static Map<String, String> sectionMap(List<String> keys, SectionVO s) {
            var map = new java.util.LinkedHashMap<String, String>();
            for (int i = 0; i < keys.size(); i++) {
                map.put(keys.get(i), s.values()[i]);
            }
            return map;
        }
    }

    /** 单段结论：4 要素有序存放（theory/practice/frontier 共用）。 */
    public record SectionVO(String[] values) {
        public SectionVO(String a, String b, String c, String d) {
            this(new String[]{a, b, c, d});
        }
    }
}
