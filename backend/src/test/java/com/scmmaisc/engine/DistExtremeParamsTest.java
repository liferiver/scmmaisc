package com.scmmaisc.engine;

import com.scmmaisc.engine.executor.CrossBorderExecutor;
import com.scmmaisc.engine.executor.ForecastExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机分布参数极端值稳定性测试（T040，spec Edge Cases）：
 * 分布参数取 σ 远大于 μ 的极端值（通关时长 mean=0.5/sd=2、mean=1/sd=3）时，
 * 仿真必须稳定（无异常、无 NaN/负时长，抽样值被截断钳制），且同 seed 可复现（FR-008）；
 * 预测场景最大噪声（σ=0.3）时约束反馈明确、序列值有下界钳制。
 */
class DistExtremeParamsTest {

    private final SimulationEngine crossBorder = new SimulationEngine(new CrossBorderExecutor());
    private final SimulationEngine forecast = new SimulationEngine(new ForecastExecutor());

    @Test
    @DisplayName("极端分布参数（σ 远大于 μ）下跨境仿真稳定：20 个种子全部无 NaN/负值")
    void crossBorderExtremeDistStable() {
        Map<String, Object> p = crossBorderExtremeParams();
        assertTrue(crossBorder.validate(p).isEmpty(), "极端分布参数（仍在声明范围内）应通过校验");

        for (long seed = 1; seed <= 20; seed++) {
            SimResult result = crossBorder.run(p, seed, null);
            // 所有输出指标数值有限
            for (OutputValue out : result.outputs()) {
                assertFiniteValue(out.value(), "seed=" + seed + " 输出 " + out.key());
            }
            // 三段耗时抽样钳制在 ≥0.5 天（正态抽样负值被截断），无负数段
            @SuppressWarnings("unchecked")
            List<Number> seg = seriesData(value(result, "segment_times"), 0);
            for (Number v : seg) {
                assertTrue(Double.isFinite(v.doubleValue()) && v.doubleValue() >= 0.5,
                        "seed=" + seed + " 段耗时 " + v + " 应 ≥0.5（截断钳制）");
            }
            // 步骤事件数据同样有限
            for (StepEvent step : result.steps()) {
                assertFiniteValue(step.data(), "seed=" + seed + " 步骤 " + step.stepNo());
            }
        }
    }

    @Test
    @DisplayName("极端分布参数下同 seed 完全复现（FR-008）")
    void crossBorderExtremeDeterministic() {
        Map<String, Object> p = crossBorderExtremeParams();
        SimResult r1 = crossBorder.run(p, 42L, null);
        SimResult r2 = crossBorder.run(p, 42L, null);
        assertEquals(r1.outputs(), r2.outputs(), "同 seed 输出必须一致");
        assertEquals(r1.steps(), r2.steps(), "同 seed 步骤事件必须一致");
    }

    @Test
    @DisplayName("dist 分布参数校验：负标准差/缺字段/类型错误/越界均返回具体原因")
    void distValidationRejectsBadValues() {
        // 负标准差（σ<0 物理上无意义）
        Map<String, Object> negSd = crossBorderExtremeParams();
        ((Map<String, Object>) negSd.get("export_clearance_time")).put("sd", -1.0);
        assertTrue(crossBorder.validate(negSd).stream().anyMatch(e -> e.contains("export_clearance_time.sd")),
                "负标准差应报错");

        // 缺字段
        Map<String, Object> missing = crossBorderExtremeParams();
        ((Map<String, Object>) missing.get("import_clearance_time")).remove("mean");
        assertTrue(crossBorder.validate(missing).stream().anyMatch(e -> e.contains("import_clearance_time.mean")),
                "缺失子字段应报错");

        // 类型错误
        Map<String, Object> wrongType = crossBorderExtremeParams();
        ((Map<String, Object>) wrongType.get("export_clearance_time")).put("mean", "快速");
        assertTrue(crossBorder.validate(wrongType).stream().anyMatch(e -> e.contains("export_clearance_time.mean")),
                "非数值应报错");

        // 越界（mean 低于下限 1）
        Map<String, Object> below = crossBorderExtremeParams();
        ((Map<String, Object>) below.get("import_clearance_time")).put("mean", 0.5);
        assertTrue(crossBorder.validate(below).stream().anyMatch(e -> e.contains("import_clearance_time.mean")),
                "越界应报错");
    }

    @Test
    @DisplayName("预测场景最大噪声（σ=0.3）：约束反馈明确，序列值钳制 ≥50 且无 NaN")
    void forecastExtremeNoiseStable() {
        Map<String, Object> p = forecastExtremeParams();
        // 约束 mape_usable（MAPE<20%）在极端噪声下无法满足 → 明确反馈而非崩溃
        List<String> errors = forecast.validate(p);
        assertFalse(errors.isEmpty(), "最大噪声应触发约束反馈");
        assertTrue(errors.stream().anyMatch(e -> e.contains("mape_usable")), "应提示约束名: " + errors);

        // 仍可运行：所有序列值有限且有下界钳制（Math.max(50, …)）
        SimResult result = forecast.run(p, 1L, null);
        @SuppressWarnings("unchecked")
        List<Number> y = seriesData(value(result, "forecast_curve"), 0);
        assertFalse(y.isEmpty(), "预测曲线不应为空");
        for (Number v : y) {
            assertTrue(Double.isFinite(v.doubleValue()) && v.doubleValue() >= 50,
                    "序列值 " + v + " 应有限且 ≥50（钳制）");
        }
        // 固定种子 42 生成需求 → 与运行 seed 无关（确定性）
        SimResult r2 = forecast.run(p, 99L, null);
        assertEquals(result.outputs(), r2.outputs(), "预测为固定种子模型，输出与运行 seed 无关");
    }

    // ---- 工具 ----

    private static Map<String, Object> crossBorderExtremeParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("trade_channel", "cn_us");
        p.put("transport_mode", "air");
        // σ 远大于 μ 的极端组合（仍处于声明范围 [mean 0.5..7, sd 0.1..2]）
        p.put("export_clearance_time", new LinkedHashMap<>(Map.of("mean", 0.5, "sd", 2.0)));
        p.put("import_clearance_time", new LinkedHashMap<>(Map.of("mean", 1.0, "sd", 3.0)));
        p.put("inspection_probability", 0.10);
        p.put("tariff_rate", 0.25);
        p.put("goods_category", "general");
        return p;
    }

    private static Map<String, Object> forecastExtremeParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("periods", 120);
        p.put("trend_strength", 0.1);
        p.put("season_period", 12);
        p.put("season_amplitude", 0.5);
        p.put("noise_level", 0.3);
        p.put("train_ratio", 0.9);
        p.put("forecast_method", "all");
        return p;
    }

    private static Object value(SimResult result, String key) {
        return result.outputs().stream().filter(o -> o.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少输出指标: " + key)).value();
    }

    /** 从 series 型输出 {x, series:[{name,data}]} 取第 idx 条曲线的 data。 */
    @SuppressWarnings("unchecked")
    private static List<Number> seriesData(Object seriesValue, int idx) {
        Map<String, Object> m = (Map<String, Object>) seriesValue;
        return (List<Number>) ((Map<String, Object>) ((List<Object>) m.get("series")).get(idx)).get("data");
    }

    /** 递归断言任意输出值不含 NaN/±∞。 */
    @SuppressWarnings("unchecked")
    private static void assertFiniteValue(Object v, String where) {
        if (v == null) return;
        if (v instanceof Number n) {
            assertTrue(Double.isFinite(n.doubleValue()), where + " 含非有限数值: " + n);
        } else if (v instanceof Map<?, ?> m) {
            m.values().forEach(child -> assertFiniteValue(child, where));
        } else if (v instanceof Iterable<?> it) {
            it.forEach(child -> assertFiniteValue(child, where));
        }
    }
}
