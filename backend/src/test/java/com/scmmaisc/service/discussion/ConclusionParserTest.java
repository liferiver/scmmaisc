package com.scmmaisc.service.discussion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConclusionParser 容错链测试（research.md §5，SC-004）：合法 JSON / 代码围栏 / 夹杂文本 /
 * 非法 JSON 触发重试 / 两次失败降级占位（12 要素仍齐全 + conclusion_note）。
 */
class ConclusionParserTest {

    private ConclusionParser parser;

    @BeforeEach
    void setUp() {
        parser = new ConclusionParser(new ObjectMapper());
    }

    @Test
    @DisplayName("合法 JSON 直接解析成功（12 要素齐全）")
    void validJsonParsed() {
        ConclusionParser.ParseResult result = parser.parse(validJson(), () -> failIfCalled());
        assertNull(result.note(), "合法输入不应降级");
        assertEquals("理论核心模型", result.conclusion().theory().values()[0]);
        assertEquals("实操参数翻译", result.conclusion().practice().values()[0]);
        assertEquals("产业前沿", result.conclusion().frontier().values()[0]);
        assertEquals(4, result.conclusion().theory().values().length);
        assertEquals(4, result.conclusion().practice().values().length);
        assertEquals(4, result.conclusion().frontier().values().length);
    }

    @Test
    @DisplayName("markdown 代码围栏包裹的 JSON 可被剥离解析")
    void jsonInsideCodeFencesParsed() {
        String fenced = "```json\n" + validJson() + "\n```";
        ConclusionParser.ParseResult result = parser.parse(fenced, () -> failIfCalled());
        assertNull(result.note());
        assertNotNull(result.conclusion());
    }

    @Test
    @DisplayName("夹杂前后缀文本时定位首个 JSON 对象解析")
    void jsonWithSurroundingTextParsed() {
        String noisy = "好的，以下是结论：\n" + validJson() + "\n——以上供参考，如有疑问欢迎继续讨论。";
        ConclusionParser.ParseResult result = parser.parse(noisy, () -> failIfCalled());
        assertNull(result.note(), "夹杂文本应可解析");
        assertEquals("理论核心模型", result.conclusion().theory().values()[0]);
    }

    @Test
    @DisplayName("非法 JSON 触发重试 1 次，重试成功则采用重试结果")
    void invalidThenRetrySucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ConclusionParser.ParseResult result = parser.parse("这不是 JSON", () -> {
            calls.incrementAndGet();
            return validJson();
        });
        assertEquals(1, calls.get(), "应恰好重试一次");
        assertNull(result.note());
        assertEquals("理论核心模型", result.conclusion().theory().values()[0]);
    }

    @Test
    @DisplayName("两次均非法 → 降级占位：12 要素齐全 + 非空 note，且不抛异常")
    void bothInvalidDegraded() {
        AtomicInteger calls = new AtomicInteger();
        ConclusionParser.ParseResult result = parser.parse("乱码", () -> {
            calls.incrementAndGet();
            return "还是乱码";
        });
        assertEquals(1, calls.get());
        assertNotNull(result.note(), "降级必须有说明");
        ConclusionParser.ConclusionVO c = result.conclusion();
        assertEquals(4, c.theory().values().length);
        assertEquals(4, c.practice().values().length);
        assertEquals(4, c.frontier().values().length);
        for (String v : c.theory().values()) {
            assertTrue(v.contains("结论生成未通过校验"), "占位内容应说明异常");
        }
    }

    @Test
    @DisplayName("缺少任一要素键 → 结构校验失败走重试（非静默通过）")
    void missingKeyFailsValidation() {
        String missing = validJson().replace("\"voteItem\"", "\"missing\"");
        AtomicInteger calls = new AtomicInteger();
        ConclusionParser.ParseResult result = parser.parse(missing, () -> {
            calls.incrementAndGet();
            return validJson();
        });
        assertEquals(1, calls.get(), "缺键应触发重试");
        assertNull(result.note());
    }

    @Test
    @DisplayName("重试源抛异常 → 降级占位且 note 含安全提示（不泄露堆栈）")
    void retryThrowsDegraded() {
        ConclusionParser.ParseResult result = parser.parse("乱码", () -> {
            throw new IllegalStateException("connection refused: 10.0.0.1:8000 (内部细节)");
        });
        assertNotNull(result.note());
        assertTrue(result.note().contains("结论生成出现异常"), "note 为通用文案");
    }

    @Test
    @DisplayName("空文本/空白 → 直接降级")
    void blankInputDegraded() {
        ConclusionParser.ParseResult result = parser.parse("   ", () -> failIfCalled());
        assertNotNull(result.note());
    }

    @Test
    @DisplayName("theoryMap/practiceMap/frontierMap 键与契约一致")
    void sectionMapsHaveContractKeys() {
        ConclusionParser.ParseResult result = parser.parse(validJson(), () -> failIfCalled());
        Map<String, String> theory = result.conclusion().theoryMap();
        Map<String, String> practice = result.conclusion().practiceMap();
        Map<String, String> frontier = result.conclusion().frontierMap();
        assertEquals(ConclusionParser.THEORY_KEYS, theory.keySet().stream().toList());
        assertEquals(ConclusionParser.PRACTICE_KEYS, practice.keySet().stream().toList());
        assertEquals(ConclusionParser.FRONTIER_KEYS, frontier.keySet().stream().toList());
    }

    @Test
    @DisplayName("扁平结构 JSON（12 键在顶层，真实模型输出形态）→ 按三段分组解析成功")
    void flatJsonParsed() {
        String flat = flatJson();
        ConclusionParser.ParseResult result = parser.parse(flat, () -> failIfCalled());
        assertNull(result.note(), "扁平结构不应降级");
        assertEquals("理论核心模型", result.conclusion().theory().values()[0]);
        assertEquals("实操参数翻译", result.conclusion().practice().values()[0]);
        assertEquals("产业前沿", result.conclusion().frontier().values()[0]);
        assertEquals(4, result.conclusion().theory().values().length);
        assertEquals(4, result.conclusion().practice().values().length);
        assertEquals(4, result.conclusion().frontier().values().length);
        assertEquals("兴趣投票", result.conclusion().frontier().values()[3]);
    }

    @Test
    @DisplayName("扁平结构缺任一要素键 → 仍走重试（非静默通过）")
    void flatJsonMissingKeyStillFails() {
        String missing = flatJson().replace("\"voteItem\"", "\"missing\"");
        AtomicInteger calls = new AtomicInteger();
        ConclusionParser.ParseResult result = parser.parse(missing, () -> {
            calls.incrementAndGet();
            return validJson();
        });
        assertEquals(1, calls.get(), "扁平缺键应触发重试");
        assertNull(result.note());
    }

    private static String failIfCalled() {
        throw new AssertionError("合法输入不应触发重试");
    }

    /** 真实模型（如 DeepSeek）常见输出形态：12 键扁平在顶层，无 theory/practice/frontier 分组。 */
    private static String flatJson() {
        return """
                {
                  "coreModel": "理论核心模型",
                  "derivation": "推导要点",
                  "assumptions": "假设边界",
                  "knowledgeLocation": "知识图谱定位",
                  "paramBusiness": "实操参数翻译",
                  "caseBenchmark": "案例对标",
                  "simRealityGap": "仿真与现实差距",
                  "suggestions": "落地建议",
                  "industry": "产业前沿",
                  "academic": "学术前沿",
                  "studentAdvice": "学生关注建议",
                  "voteItem": "兴趣投票"
                }
                """;
    }

    private static String validJson() {
        return """
                {
                  "theory": {
                    "coreModel": "理论核心模型",
                    "derivation": "推导要点",
                    "assumptions": "假设边界",
                    "knowledgeLocation": "知识图谱定位"
                  },
                  "practice": {
                    "paramBusiness": "实操参数翻译",
                    "caseBenchmark": "案例对标",
                    "simRealityGap": "仿真与现实差距",
                    "suggestions": "落地建议"
                  },
                  "frontier": {
                    "industry": "产业前沿",
                    "academic": "学术前沿",
                    "studentAdvice": "学生关注建议",
                    "voteItem": "兴趣投票"
                  }
                }
                """;
    }
}
