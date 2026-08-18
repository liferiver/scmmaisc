package com.scmmaisc.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义化标量组约束提取器（T066，V11 / ui.md 二期）：从场景约束表达式中识别
 * 「纯求和比较」形态（p1 + p2 + ... + pn <op> 常量|参数），随 C3 场景详情下发，
 * 供前端按参数组即时校验并阻止提交（如 CH1-001 的 weight_* 六维权重和 = 1）。
 *
 * <p>仅提取全部求和项为已知参数、右端为数值常量或已知参数的表达式；含系数
 * （如 {@code fx_hedge_ratio + backup_options * 0.2 + ...}）、函数（count(...)）、
 * 链式比较、中文占位等复杂形态不提取——这些约束仍由后端执行器在 validate() 中兜底
 * （FR-005），前端提取结果不影响后端校验。</p>
 */
public final class GroupConstraintExtractor {

    /** 求和比较形态：求和串（≥2 个标识符）+ 比较符（常量|标识符）。group1=求和串、group2=操作符、group3=右端。 */
    private static final Pattern SUM_CMP = Pattern.compile(
            "^\\s*((?:[A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\+\\s*[A-Za-z_][A-Za-z0-9_]*)+)\\s*(<=|>=|==|!=|<|>)\\s*([A-Za-z0-9_.-]+)\\s*$");

    /** 组约束：params 为求和参数；op 为比较符；target 为数值常量（targetParam 为空时）或参数 key。 */
    public record GroupConstraint(String name, String message, List<String> params,
                                  String op, Double target, String targetParam) {
    }

    private GroupConstraintExtractor() {
    }

    /** 从场景约束列表提取纯求和比较约束；无匹配时返回空列表。 */
    public static List<GroupConstraint> extract(List<Map<String, Object>> constraints, Set<String> paramKeys) {
        List<GroupConstraint> groups = new ArrayList<>();
        if (constraints == null) {
            return groups;
        }
        for (Map<String, Object> c : constraints) {
            if (!(c.get("name") instanceof String name) || !(c.get("expression") instanceof String expr)) {
                continue;
            }
            Matcher m = SUM_CMP.matcher(expr);
            if (!m.matches()) {
                continue;
            }
            // group(1) 为完整求和串（如 "a + b + c"），按 "+ 空格" 切分得到全部求和参数
            List<String> params = new ArrayList<>();
            for (String term : m.group(1).split("\\s*\\+\\s*")) {
                if (!term.isEmpty()) {
                    params.add(term);
                }
            }
            if (!paramKeys.containsAll(params)) {
                continue;
            }
            String op = m.group(2);
            String rhs = m.group(3);
            if (paramKeys.contains(rhs)) {
                groups.add(new GroupConstraint(name, message(c), params, op, null, rhs));
            } else {
                try {
                    double target = Double.parseDouble(rhs);
                    groups.add(new GroupConstraint(name, message(c), params, op, target, null));
                } catch (NumberFormatException ignored) {
                    // 右端既非数值也非参数（如中文占位）→ 跳过
                }
            }
        }
        return groups;
    }

    private static String message(Map<String, Object> c) {
        return c.get("message") instanceof String m && !m.isBlank() ? m : String.valueOf(c.get("name"));
    }
}
