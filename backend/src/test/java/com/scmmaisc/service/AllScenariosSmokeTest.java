package com.scmmaisc.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scmmaisc.engine.ExecutorRegistry;
import com.scmmaisc.engine.OutputValue;
import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimResult;
import com.scmmaisc.engine.SimulationEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二期全量冒烟测试（T063，SC-009）：84 个场景 JSON 全量校验 + 默认参数 validate/run 冒烟。
 *
 * <p>校验维度：</p>
 * <ol>
 *   <li><b>Schema</b>：必填字段、参数 key 唯一、输出 key 唯一、约束表达式可分词/可解析；</li>
 *   <li><b>注册</b>：engineKey 均有对应执行器 bean（零配置注册闭环）；</li>
 *   <li><b>冒烟</b>：默认参数 validate 通过、run(seed=42) 无异常、产出全部声明输出、
 *       未声明输出 key 会被捕获（执行器与 JSON 契约一致性）；</li>
 *   <li><b>约束求值</b>：约束表达式以「默认参数 + 标量输出」为环境求值并输出报告
 *       （含链式比较、count() 数组计数、字符串/布尔相等、展示型中文占位表达式）。</li>
 * </ol>
 */
@SpringBootTest
class AllScenariosSmokeTest {

    private static final int EXPECTED_SCENARIOS = 84;
    private static final Set<String> PARAM_TYPES = Set.of("int", "float", "bool", "enum", "matrix", "dist", "timeseries");
    private static final Set<String> OUTPUT_TYPES = Set.of("scalar", "compare", "series", "gauge",
            "heatmap", "topo", "dist", "matrix", "timeseries");

    @Autowired
    private ExecutorRegistry registry;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("T063: 84 场景 schema 校验 + 默认参数 validate/run 冒烟")
    void allScenariosSchemaAndRunSmoke() throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:scenarios/*.json");
        assertEquals(EXPECTED_SCENARIOS, resources.length, "二期场景 JSON 总数应为 84");

        List<String> failures = new ArrayList<>();
        List<String> reports = new ArrayList<>();
        int runCount = 0;
        for (Resource resource : resources) {
            String file = resource.getFilename();
            Map<String, Object> def = objectMapper.readValue(resource.getInputStream(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            String moduleId = str(def, "moduleId");
            try {
                schemaValidate(def, file, failures);
                Map<String, Object> defaults = defaultParams(def, file, failures);
                String engineKey = str(def, "engineKey");
                Optional<ScenarioExecutor> exec = registry.find(engineKey);
                if (exec.isEmpty()) {
                    failures.add(file + " " + moduleId + ": 执行器未注册 engineKey=" + engineKey);
                    continue;
                }
                SimulationEngine engine = new SimulationEngine(exec.get());
                List<String> vErrors = engine.validate(new LinkedHashMap<>(defaults));
                if (!vErrors.isEmpty()) {
                    failures.add(file + " " + moduleId + ": 默认参数 validate 失败 → " + vErrors);
                }
                SimResult result = engine.run(new LinkedHashMap<>(defaults), 42L, null);
                if (result.steps().isEmpty()) {
                    failures.add(file + " " + moduleId + ": run 未产生任何步骤");
                }
                outputsConsistency(def, result, file, failures);
                constraintsEval(def, defaults, result, moduleId, reports);
                runCount++;
            } catch (Exception e) {
                failures.add(file + " " + moduleId + ": 冒烟异常 → " + e);
            }
        }
        assertEquals(EXPECTED_SCENARIOS, runCount,
                "全部场景应完成冒烟，失败项:\n" + String.join("\n", failures));
        if (!reports.isEmpty()) {
            System.out.println("== 约束求值报告（共 " + reports.size() + " 条） ==");
            reports.forEach(System.out::println);
        }
        assertTrue(failures.isEmpty(), "冒烟失败项:\n" + String.join("\n", failures));
    }

    // ---- Schema 校验 ----

    @SuppressWarnings("unchecked")
    private void schemaValidate(Map<String, Object> def, String file, List<String> failures) {
        String moduleId = str(def, "moduleId");
        requireText(def, "moduleId", file, failures);
        requireText(def, "name", file, failures);
        requireText(def, "engineKey", file, failures);
        requireText(def, "difficulty", file, failures);
        requireText(def, "concept", file, failures);
        requireText(def, "description", file, failures);
        if (!(def.get("classHours") instanceof Number)) {
            failures.add(file + " " + moduleId + ": classHours 缺失或非数值");
        }
        if (!(def.get("isRolePlay") instanceof Boolean)) {
            failures.add(file + " " + moduleId + ": isRolePlay 缺失或非布尔");
        }
        if (!(def.get("deps") instanceof List)) {
            failures.add(file + " " + moduleId + ": deps 缺失或非数组");
        }
        Map<String, Object> chapter = asMap(def.get("chapter"));
        if (chapter == null || str(chapter, "code").isBlank() || str(chapter, "name").isBlank()
                || !(chapter.get("sortNo") instanceof Number)) {
            failures.add(file + " " + moduleId + ": chapter{code,name,sortNo} 缺失");
        }

        List<Map<String, Object>> params = listOf(def.get("params"), file + " " + moduleId + ": params", failures);
        Set<String> paramKeys = new HashSet<>();
        if (params != null) {
            for (Map<String, Object> p : params) {
                String key = str(p, "key");
                if (key.isBlank() || !paramKeys.add(key)) {
                    failures.add(file + " " + moduleId + ": 参数 key 缺失或重复 → " + key);
                }
                if (str(p, "label").isBlank()) {
                    failures.add(file + " " + moduleId + ": 参数 " + key + " label 缺失");
                }
                String type = str(p, "type");
                if (!PARAM_TYPES.contains(type)) {
                    failures.add(file + " " + moduleId + ": 参数 " + key + " 类型非法 → " + type);
                }
                checkDefault(p, key, type, file, moduleId, failures);
            }
        }

        List<Map<String, Object>> outputs = listOf(def.get("outputs"), file + " " + moduleId + ": outputs", failures);
        Set<String> outputKeys = new HashSet<>();
        if (outputs != null) {
            for (Map<String, Object> o : outputs) {
                String key = str(o, "key");
                if (key.isBlank() || !outputKeys.add(key)) {
                    failures.add(file + " " + moduleId + ": 输出 key 缺失或重复 → " + key);
                }
                if (str(o, "label").isBlank()) {
                    failures.add(file + " " + moduleId + ": 输出 " + key + " label 缺失");
                }
                if (!OUTPUT_TYPES.contains(str(o, "type"))) {
                    failures.add(file + " " + moduleId + ": 输出 " + key + " 类型非法 → " + str(o, "type"));
                }
            }
        }

        List<Map<String, Object>> constraints = listOf(def.get("constraints"),
                file + " " + moduleId + ": constraints", failures);
        Set<String> constraintNames = new HashSet<>();
        if (constraints != null) {
            for (Map<String, Object> c : constraints) {
                String name = str(c, "name");
                if (name.isBlank() || !constraintNames.add(name)) {
                    failures.add(file + " " + moduleId + ": 约束 name 缺失或重复 → " + name);
                }
                String expr = str(c, "expression");
                if (expr.isBlank() || str(c, "message").isBlank()) {
                    failures.add(file + " " + moduleId + ": 约束 " + name + " expression/message 缺失");
                } else if (!isDisplayOnly(expr)) {
                    try {
                        new ConstraintEvaluator(expr, Map.of()).parseOnly();
                    } catch (DisplayOnlyException ignored) {
                        // 展示型占位（如 count() 标识符在无环境时不可解析），跳过解析校验
                    } catch (RuntimeException e) {
                        failures.add(file + " " + moduleId + ": 约束 " + name + " 表达式不可解析 → "
                                + expr + "（" + e.getMessage() + "）");
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void checkDefault(Map<String, Object> p, String key, String type,
                              String file, String moduleId, List<String> failures) {
        Object def = p.get("default");
        switch (type) {
            case "int":
            case "float":
                if (!(def instanceof Number)) {
                    failures.add(file + " " + moduleId + ": 参数 " + key + " 缺少数值默认值");
                } else {
                    Number min = p.get("min") instanceof Number mn ? mn : null;
                    Number max = p.get("max") instanceof Number mx ? mx : null;
                    if (min != null && max != null && min.doubleValue() > max.doubleValue()) {
                        failures.add(file + " " + moduleId + ": 参数 " + key + " min>max");
                    }
                }
                break;
            case "bool":
                if (!(def instanceof Boolean)) {
                    failures.add(file + " " + moduleId + ": 参数 " + key + " 缺少布尔默认值");
                }
                break;
            case "enum":
                Object options = p.get("options");
                if (!(options instanceof List) || ((List<?>) options).isEmpty()) {
                    failures.add(file + " " + moduleId + ": 参数 " + key + " options 缺失");
                } else if (def == null) {
                    failures.add(file + " " + moduleId + ": 参数 " + key + " 缺少枚举默认值");
                } else {
                    boolean matched = false;
                    for (Object o : (List<?>) options) {
                        if (o instanceof Map<?, ?> m && def.equals(m.get("value"))) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        failures.add(file + " " + moduleId + ": 参数 " + key + " 默认值不在 options → " + def);
                    }
                }
                break;
            default: // matrix/dist/timeseries 允许无默认值（执行器合成或缺省兜底）
                if (def != null && type.equals("matrix") && !(def instanceof List)) {
                    failures.add(file + " " + moduleId + ": 参数 " + key + " matrix 默认值需为数组");
                }
                if (def != null && type.equals("dist") && !(def instanceof Map)) {
                    failures.add(file + " " + moduleId + ": 参数 " + key + " dist 默认值需为对象");
                }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaultParams(Map<String, Object> def, String file, List<String> failures) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        List<Map<String, Object>> params = listOf(def.get("params"), file + ": params", failures);
        if (params == null) {
            return defaults;
        }
        for (Map<String, Object> p : params) {
            String key = str(p, "key");
            if (p.get("default") != null) {
                defaults.put(key, p.get("default"));
            }
        }
        return defaults;
    }

    private void outputsConsistency(Map<String, Object> def, SimResult result, String file, List<String> failures) {
        String moduleId = str(def, "moduleId");
        Set<String> declared = new HashSet<>();
        for (Map<String, Object> o : listOf(def.get("outputs"), file, failures)) {
            declared.add(str(o, "key"));
        }
        Set<String> produced = new HashSet<>();
        for (OutputValue o : result.outputs()) {
            if (!produced.add(o.key())) {
                failures.add(file + " " + moduleId + ": 执行器重复输出 key → " + o.key());
            }
        }
        for (String key : declared) {
            if (!produced.contains(key)) {
                failures.add(file + " " + moduleId + ": 缺少声明输出 → " + key);
            }
        }
        for (String key : produced) {
            if (!declared.contains(key)) {
                failures.add(file + " " + moduleId + ": 执行器输出了未声明 key → " + key);
            }
        }
    }

    // ---- 约束求值 ----

    private void constraintsEval(Map<String, Object> def, Map<String, Object> defaults,
                                 SimResult result, String moduleId, List<String> reports) {
        Map<String, Object> env = new LinkedHashMap<>(defaults);
        for (OutputValue o : result.outputs()) {
            if (o.value() instanceof Number || o.value() instanceof String || o.value() instanceof Boolean) {
                env.put(o.key(), o.value());
            }
        }
        List<Map<String, Object>> constraints = listOf(def.get("constraints"), moduleId + ": constraints", null);
        if (constraints == null) {
            return;
        }
        for (Map<String, Object> c : constraints) {
            String name = str(c, "name");
            String expr = str(c, "expression");
            if (isDisplayOnly(expr)) {
                reports.add(moduleId + " 约束[" + name + "] 展示型文本（不参与数值求值）");
                continue;
            }
            try {
                double v = new ConstraintEvaluator(expr, env).evaluate();
                reports.add(moduleId + " 约束[" + name + "] → " + (v != 0 ? "满足" : "不满足") + "（" + expr + "）");
            } catch (DisplayOnlyException e) {
                reports.add(moduleId + " 约束[" + name + "] 展示型/不可求值（" + e.getMessage() + "）");
            } catch (RuntimeException e) {
                reports.add(moduleId + " 约束[" + name + "] 求值失败（" + e.getMessage() + "）");
            }
        }
    }

    /** 展示型表达式：不含任何比较/相等/逻辑运算符（如「成本/时间/质量/效率 四维度均有目标」）。 */
    private static boolean isDisplayOnly(String expr) {
        return !expr.matches(".*(<=|>=|==|!=|=|<|>|\\band\\b|\\bor\\b).*");
    }

    // ---- 工具 ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o, String where, List<String> failures) {
        if (!(o instanceof List)) {
            if (failures != null) {
                failures.add(where + " 缺失或非数组");
            }
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : (List<?>) o) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            } else if (failures != null) {
                failures.add(where + " 元素非对象");
            }
        }
        return out;
    }

    private static void requireText(Map<String, Object> map, String key, String file, List<String> failures) {
        if (str(map, key).isBlank()) {
            failures.add(file + " " + str(map, "moduleId") + ": 必填字段缺失 → " + key);
        }
    }

    // ---- 极简约束表达式求值器 ----

    /** 展示型占位（中文文本/类型不匹配），非解析/求值错误。 */
    static final class DisplayOnlyException extends RuntimeException {
        DisplayOnlyException(String msg) {
            super(msg);
        }
    }

    /**
     * 约束表达式求值器：支持数值/字符串/布尔字面量、四则运算、比较（含链式 a<=b<=c）、
     * and/or 逻辑、count(数组标识符 比较 阈值) 数组计数；标识符按「参数默认值 ∪ 标量输出」解析
     * （大小写不敏感），"Q*" 归一为 "Q_star" 后按 q_star 解析。
     */
    static final class ConstraintEvaluator {
        private final String[] tokens;
        private final Map<String, Object> env;
        private int pos;

        ConstraintEvaluator(String expr, Map<String, Object> env) {
            this.tokens = tokenize(normalize(expr));
            this.env = env;
        }

        /** 仅解析校验（不解析标识符）。 */
        void parseOnly() {
            parseOr();
            if (pos != tokens.length) {
                throw new IllegalStateException("多余 token: " + tokens[pos]);
            }
        }

        double evaluate() {
            double v = parseOr();
            if (pos != tokens.length) {
                throw new IllegalStateException("多余 token: " + tokens[pos]);
            }
            return v;
        }

        private static String normalize(String expr) {
            // "Q*" → "Q_star"（最优量占位），避免与乘法冲突；要求 * 紧邻标识符
            return expr.replaceAll("([A-Za-z_\\u4e00-\\u9fa5][A-Za-z0-9_\\u4e00-\\u9fa5]*)\\*", "$1_star");
        }

        private static String[] tokenize(String s) {
            List<String> out = new ArrayList<>();
            int i = 0;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (Character.isDigit(c)) {
                    int j = i;
                    while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                        j++;
                    }
                    out.add(s.substring(i, j));
                    i = j;
                } else if (c == '\'' || c == '"') {
                    int j = i + 1;
                    while (j < n && s.charAt(j) != c) {
                        j++;
                    }
                    out.add(s.substring(i + 1, Math.min(j, n)));
                    i = Math.min(j + 1, n);
                } else if (Character.isLetter(c) || c == '_') {
                    int j = i;
                    while (j < n && (Character.isLetter(s.charAt(j)) || Character.isDigit(s.charAt(j))
                            || s.charAt(j) == '_')) {
                        j++;
                    }
                    out.add(s.substring(i, j));
                    i = j;
                } else if (i + 1 < n) {
                    String two = s.substring(i, i + 2);
                    if (two.equals("<=") || two.equals(">=") || two.equals("==") || two.equals("!=")
                            || two.equals("&&") || two.equals("||")) {
                        out.add(two);
                        i += 2;
                    } else {
                        out.add(String.valueOf(c));
                        i++;
                    }
                } else {
                    out.add(String.valueOf(c));
                    i++;
                }
            }
            return out.toArray(new String[0]);
        }

        // ---- 语法：or → and → compare → add → mul → unary → primary ----

        private double parseOr() {
            double v = parseAnd();
            while (pos < tokens.length && (tokens[pos].equals("or") || tokens[pos].equals("||"))) {
                pos++;
                double r = parseAnd();
                v = (v != 0 || r != 0) ? 1 : 0;
            }
            return v;
        }

        private double parseAnd() {
            double v = parseCompare();
            while (pos < tokens.length && (tokens[pos].equals("and") || tokens[pos].equals("&&"))) {
                pos++;
                double r = parseCompare();
                v = (v != 0 && r != 0) ? 1 : 0;
            }
            return v;
        }

        private double parseCompare() {
            Object left = parseAdd();
            if (pos >= tokens.length || !isCompareOp(tokens[pos])) {
                return toNumber(left);
            }
            String op = tokens[pos++];
            Object right = parseAdd();
            boolean first = compare(left, op, right);
            if (pos < tokens.length && isCompareOp(tokens[pos])) { // 链式 a <= b <= c
                String op2 = tokens[pos++];
                Object right2 = parseAdd();
                return (first && compare(right, op2, right2)) ? 1 : 0;
            }
            return first ? 1 : 0;
        }

        private Object parseAdd() {
            Object v = parseMul();
            while (pos < tokens.length && (tokens[pos].equals("+") || tokens[pos].equals("-"))) {
                String op = tokens[pos++];
                Object r = parseMul();
                v = arith(v, op, r);
            }
            return v;
        }

        private Object parseMul() {
            Object v = parseUnary();
            while (pos < tokens.length && (tokens[pos].equals("*") || tokens[pos].equals("/"))) {
                String op = tokens[pos++];
                Object r = parseUnary();
                v = arith(v, op, r);
            }
            return v;
        }

        private Object parseUnary() {
            if (pos < tokens.length && tokens[pos].equals("-")) {
                pos++;
                Object v = parseUnary();
                return toNumber(v) * -1;
            }
            return parsePrimary();
        }

        private Object parsePrimary() {
            if (pos >= tokens.length) {
                throw new IllegalStateException("表达式意外结束");
            }
            String tok = tokens[pos++];
            if (tok.equals("(")) {
                double v = parseOr();
                expect(")");
                return v;
            }
            if (isNumber(tok)) {
                return Double.parseDouble(tok);
            }
            if (tok.equals("true") || tok.equals("false")) {
                return Boolean.parseBoolean(tok);
            }
            if (tok.equals("count")) {
                return countPattern();
            }
            Object resolved = resolve(tok);
            if (resolved != null) {
                return resolved;
            }
            return tok; // 裸词（如 inhouse / 最小可接单量）按字符串处理
        }

        /** count(数组标识符 比较 阈值)：统计数组中满足比较的元素个数（支持二维矩阵拍平）。 */
        private double countPattern() {
            expect("(");
            String ident = tokens[pos++];
            String op = tokens[pos++];
            Object threshold = parsePrimary();
            expect(")");
            Object arr = resolve(ident);
            if (arr == null) {
                throw new DisplayOnlyException("count() 标识符不可解析: " + ident);
            }
            int cnt = 0;
            if (arr instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof List<?> inner) {
                        for (Object v : inner) {
                            if (v instanceof Number num && compare(num.doubleValue(), op, threshold)) {
                                cnt++;
                            }
                        }
                    } else if (o instanceof Number num && compare(num.doubleValue(), op, threshold)) {
                        cnt++;
                    }
                }
            }
            return cnt;
        }

        private Object resolve(String ident) {
            for (Map.Entry<String, Object> e : env.entrySet()) {
                if (e.getKey().equalsIgnoreCase(ident)) {
                    return e.getValue();
                }
            }
            return null;
        }

        private static boolean isNumber(String tok) {
            return tok.chars().allMatch(c -> Character.isDigit(c) || c == '.');
        }

        private static boolean isCompareOp(String tok) {
            return tok.equals("<") || tok.equals("<=") || tok.equals(">") || tok.equals(">=")
                    || tok.equals("=") || tok.equals("==") || tok.equals("!=");
        }

        private void expect(String tok) {
            if (pos >= tokens.length || !tokens[pos].equals(tok)) {
                throw new IllegalStateException("期望 " + tok + "，实际: "
                        + (pos < tokens.length ? tokens[pos] : "表达式结束"));
            }
            pos++;
        }

        private static double toNumber(Object v) {
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof Boolean b) {
                return b ? 1 : 0;
            }
            throw new DisplayOnlyException("非数值参与算术/逻辑: " + v);
        }

        private static Object arith(Object a, String op, Object b) {
            double x = toNumber(a);
            double y = toNumber(b);
            switch (op) {
                case "+":
                    return x + y;
                case "-":
                    return x - y;
                case "*":
                    return x * y;
                case "/":
                    return x / y;
                default:
                    throw new IllegalStateException("非法运算符: " + op);
            }
        }

        @SuppressWarnings("unchecked")
        private static boolean compare(Object a, String op, Object b) {
            if (a instanceof Number na && b instanceof Number nb) {
                double x = na.doubleValue();
                double y = nb.doubleValue();
                switch (op) {
                    case "<":
                        return x < y;
                    case "<=":
                        return x <= y;
                    case ">":
                        return x > y;
                    case ">=":
                        return x >= y;
                    case "=":
                    case "==":
                        return Math.abs(x - y) < 1e-9;
                    default:
                        return Math.abs(x - y) >= 1e-9;
                }
            }
            String sa = stringOf(a);
            String sb = stringOf(b);
            switch (op) {
                case "=":
                case "==":
                    return sa.equals(sb);
                case "!=":
                    return !sa.equals(sb);
                case "<":
                    return sa.compareTo(sb) < 0;
                case "<=":
                    return sa.compareTo(sb) <= 0;
                case ">":
                    return sa.compareTo(sb) > 0;
                case ">=":
                    return sa.compareTo(sb) >= 0;
                default:
                    throw new IllegalStateException("非法比较: " + op);
            }
        }

        private static String stringOf(Object v) {
            if (v instanceof String s) {
                return s;
            }
            if (v instanceof Boolean b) {
                return b.toString();
            }
            throw new DisplayOnlyException("类型不匹配，无法比较: " + v);
        }
    }
}
