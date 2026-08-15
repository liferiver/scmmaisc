package com.scmmaisc.engine.executor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行器公共校验/计算工具（T025–T030）：参数解析（int/float/enum/bool/dist/matrix）、
 * 报错文案与 EoqExecutor 保持一致（"参数 X 缺失 / 必须为整数 / 超出范围 [min, max]"）。
 */
public final class ExecutorSupport {

    private ExecutorSupport() {
    }

    /** 整数参数：缺失/类型错误/越界 → 记录错误并返回 null。 */
    public static Integer intParam(Map<String, Object> params, String key, int min, int max, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof Number)) {
            errors.add("参数 " + key + " 必须为整数");
            return null;
        }
        int v = ((Number) raw).intValue();
        if (v < min || v > max) {
            errors.add(key + " 超出范围 [" + min + ", " + max + "]");
            return null;
        }
        return v;
    }

    /** 数值参数（float）：缺失/类型错误/越界 → 记录错误并返回 null。 */
    public static Double doubleParam(Map<String, Object> params, String key, double min, double max, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof Number)) {
            errors.add("参数 " + key + " 必须为数值");
            return null;
        }
        double v = ((Number) raw).doubleValue();
        if (v < min || v > max) {
            errors.add(key + " 超出范围 [" + min + ", " + max + "]");
            return null;
        }
        return v;
    }

    /** 枚举参数：必须在 options 内。 */
    public static String enumParam(Map<String, Object> params, String key, Set<String> options, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        String v = String.valueOf(raw);
        if (!options.contains(v)) {
            errors.add(key + " 必须为 " + options + " 之一");
            return null;
        }
        return v;
    }

    /** 布尔参数。 */
    public static Boolean boolParam(Map<String, Object> params, String key, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof Boolean)) {
            errors.add("参数 " + key + " 必须为布尔值");
            return null;
        }
        return (Boolean) raw;
    }

    /** dist 分布参数：字段级校验（浮点），返回 field → Double。 */
    public static Map<String, Double> distParam(Map<String, Object> params, String key,
                                                Map<String, double[]> fields, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof Map<?, ?> group)) {
            errors.add("参数 " + key + " 必须为分布对象");
            return null;
        }
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : fields.entrySet()) {
            Object v = group.get(e.getKey());
            if (v == null) {
                errors.add(key + "." + e.getKey() + " 缺失");
                continue;
            }
            if (!(v instanceof Number)) {
                errors.add(key + "." + e.getKey() + " 必须为数值");
                continue;
            }
            double d = ((Number) v).doubleValue();
            double[] range = e.getValue();
            if (d < range[0] || d > range[1]) {
                errors.add(key + "." + e.getKey() + " 超出范围 [" + range[0] + ", " + range[1] + "]");
                continue;
            }
            out.put(e.getKey(), d);
        }
        return out;
    }

    /** matrix 矩阵参数：校验数值矩阵与尺寸，返回 double[][]（rows 行 × cols 列）。 */
    public static double[][] matrixParam(Map<String, Object> params, String key,
                                         int minRows, int maxRows, int cols, double min, double max,
                                         List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof List<?> rows)) {
            errors.add("参数 " + key + " 必须为矩阵");
            return null;
        }
        if (rows.size() < minRows || rows.size() > maxRows) {
            errors.add(key + " 行数超出范围 [" + minRows + ", " + maxRows + "]");
            return null;
        }
        double[][] out = new double[rows.size()][cols];
        for (int r = 0; r < rows.size(); r++) {
            Object rowRaw = rows.get(r);
            if (!(rowRaw instanceof List<?> row)) {
                errors.add(key + " 第 " + (r + 1) + " 行必须为数组");
                return null;
            }
            if (row.size() != cols) {
                errors.add(key + " 每行必须为 " + cols + " 列");
                return null;
            }
            for (int c = 0; c < cols; c++) {
                Object cell = row.get(c);
                if (!(cell instanceof Number)) {
                    errors.add(key + "[" + r + "][" + c + "] 必须为数字");
                    return null;
                }
                double v = ((Number) cell).doubleValue();
                if (v < min || v > max) {
                    errors.add(key + "[" + r + "][" + c + "] 超出范围 [" + min + ", " + max + "]");
                    return null;
                }
                out[r][c] = v;
            }
        }
        return out;
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 构建 series 型输出值：{x: [...], series: [{name, data}]}。 */
    public static Map<String, Object> series(List<? extends Number> x, String name, List<? extends Number> data) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("x", x);
        m.put("series", List.of(Map.of("name", name, "data", data)));
        return m;
    }
}
