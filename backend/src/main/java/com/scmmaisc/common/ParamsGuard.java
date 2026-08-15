package com.scmmaisc.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参数白名单与结构守卫（T041，宪法"安全"约束）：
 * 限制 params 的条目数、嵌套深度与值类型，防止异常构造的请求体
 * （超长字符串、超深嵌套、超大集合）进入引擎或落库。
 */
public final class ParamsGuard {

    /** 顶层参数条目上限。 */
    private static final int MAX_ENTRIES = 50;

    /** 允许的最大嵌套深度（根为 1）。 */
    private static final int MAX_DEPTH = 4;

    /** 单条字符串值长度上限。 */
    private static final int MAX_STRING_LENGTH = 256;

    private ParamsGuard() {
    }

    /** 校验 params 结构；返回问题列表，空 = 通过。 */
    public static List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        if (params == null) {
            return errors;
        }
        if (params.size() > MAX_ENTRIES) {
            errors.add("参数数量超限（最多 " + MAX_ENTRIES + " 个）");
            return errors;
        }
        checkValue(params, 1, errors);
        return errors;
    }

    private static void checkValue(Object value, int depth, List<String> errors) {
        if (value == null) {
            return;
        }
        if (value instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) {
                errors.add("参数值必须为有限数值（不允许 NaN/无穷）");
            }
            return;
        }
        if (value instanceof Boolean || value instanceof String) {
            if (value instanceof String s && s.length() > MAX_STRING_LENGTH) {
                errors.add("字符串参数长度超限（最多 " + MAX_STRING_LENGTH + " 字符）");
            }
            return;
        }
        if (value instanceof Map<?, ?> m) {
            if (depth >= MAX_DEPTH) {
                errors.add("参数嵌套层级过深（最多 " + MAX_DEPTH + " 层）");
                return;
            }
            for (Object child : m.values()) {
                checkValue(child, depth + 1, errors);
            }
            return;
        }
        if (value instanceof Iterable<?> it) {
            if (depth >= MAX_DEPTH) {
                errors.add("参数嵌套层级过深（最多 " + MAX_DEPTH + " 层）");
                return;
            }
            for (Object child : it) {
                checkValue(child, depth + 1, errors);
            }
            return;
        }
        errors.add("参数包含不支持的值类型: " + value.getClass().getSimpleName());
    }
}
