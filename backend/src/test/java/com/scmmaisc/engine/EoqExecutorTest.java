package com.scmmaisc.engine;

import com.scmmaisc.engine.executor.EoqExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EOQ 执行器单元测试（T020）：教材算例数值、参数范围校验、约束校验、seed 无关性。
 * 参数 key 约定（T024 与此保持一致）：annual_demand / order_cost / holding_cost /
 * lead_time / daily_demand / order_qty。
 */
class EoqExecutorTest {

    private final SimulationEngine engine = new SimulationEngine(new EoqExecutor());

    /** 教材算例：D=10000, S=100, H=2 → Q*=1000，年总成本=2000（quickstart V2，误差 0）。 */
    @Test
    @DisplayName("教材算例：Q*=1000、年总成本=2000、ROP=192、年订货 10 次、平均库存 500")
    void textbookCase() {
        SimResult result = engine.run(baseParams(), 42L, null);

        assertEquals(1000.0, scalar(result, "q_star"), 1e-9, "Q* 应为 1000");
        assertEquals(2000.0, scalar(result, "total_cost"), 1e-9, "年总成本应为 2000");
        assertEquals(192.0, scalar(result, "rop"), 1e-9, "ROP = round(27.4×7) = 192");
        assertEquals(10.0, scalar(result, "order_count"), 1e-9, "年订货次数 = D/Q = 10");
        assertEquals(500.0, scalar(result, "avg_inventory"), 1e-9, "平均库存 = Q/2 = 500");

        @SuppressWarnings("unchecked")
        Map<String, Object> curve = (Map<String, Object>) value(result, "annual_cost_curve");
        List<Number> x = (List<Number>) curve.get("x");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) curve.get("series");
        assertEquals(25, x.size(), "成本曲线 25 个采样点（0.5Q*~1.5Q*）");
        assertEquals(1, series.size());
        List<Number> y = (List<Number>) series.get(0).get("data");
        int idx = x.indexOf(1000.0);
        assertTrue(idx >= 0, "曲线应包含 Q*=1000 的采样点");
        assertEquals(2000.0, y.get(idx).doubleValue(), 1e-9, "Q=Q* 处年总成本 = 2000");
    }

    @Test
    @DisplayName("参数范围校验：越界/缺失/非法类型均返回具体原因")
    void validationRanges() {
        // 缺失必填参数
        List<String> missing = engine.validate(new LinkedHashMap<>());
        assertTrue(missing.stream().anyMatch(e -> e.contains("annual_demand")), "缺失参数应报错: " + missing);

        // 越界
        Map<String, Object> low = with("annual_demand", 50);
        assertTrue(engine.validate(low).stream().anyMatch(e -> e.contains("annual_demand")), "D=50 低于下限应报错");
        Map<String, Object> high = with("order_cost", 99999.0);
        assertTrue(engine.validate(high).stream().anyMatch(e -> e.contains("order_cost")), "C2 超上限应报错");
        Map<String, Object> zero = with("holding_cost", 0.0);
        assertTrue(engine.validate(zero).stream().anyMatch(e -> e.contains("holding_cost")), "C1=0 应报错");
        Map<String, Object> badLead = with("lead_time", 31.0);
        assertTrue(engine.validate(badLead).stream().anyMatch(e -> e.contains("lead_time")), "L=31 超出 [1,30] 应报错");

        // 类型错误（字符串代替数值）
        Map<String, Object> wrongType = with("annual_demand", "一万");
        assertTrue(engine.validate(wrongType).stream().anyMatch(e -> e.contains("annual_demand")), "类型非法应报错");

        // 合法参数通过
        assertTrue(engine.validate(baseParams()).isEmpty(), "合法参数不应有校验错误");
    }

    @Test
    @DisplayName("约束校验：order_qty 偏离 Q* 超过 ±50% 报错，边界 0.5Q* / 1.5Q* 通过")
    void constraintOrderQty() {
        // Q*=1000 → 允许范围 [500, 1500]
        Map<String, Object> tooHigh = with("order_qty", 2000);
        List<String> errors = engine.validate(tooHigh);
        assertTrue(errors.stream().anyMatch(e -> e.contains("order_qty") && e.contains("50%")),
                "order_qty=2000 偏离 100% 应报 ±50% 约束错误: " + errors);

        assertTrue(engine.validate(with("order_qty", 500)).isEmpty(), "order_qty=0.5Q* 边界应通过");
        assertTrue(engine.validate(with("order_qty", 1500)).isEmpty(), "order_qty=1.5Q* 边界应通过");
        assertTrue(engine.validate(with("order_qty", 0)).isEmpty(), "order_qty=0（使用 Q*）应通过");
    }

    @Test
    @DisplayName("seed 无关性：EOQ 为确定性模型，不同 seed 结果完全一致")
    void seedIndependence() {
        SimResult r1 = engine.run(baseParams(), 1L, null);
        SimResult r2 = engine.run(baseParams(), 2L, null);
        assertEquals(r1.steps(), r2.steps(), "不同 seed 步骤事件应一致");
        assertEquals(r1.outputs(), r2.outputs(), "不同 seed 输出指标应一致");
    }

    // ---- 工具 ----

    private static Map<String, Object> baseParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("annual_demand", 10000);
        p.put("order_cost", 100.0);
        p.put("holding_cost", 2.0);
        p.put("lead_time", 7.0);
        p.put("daily_demand", 27.4);
        p.put("order_qty", 0);
        return p;
    }

    private static Map<String, Object> with(String key, Object value) {
        Map<String, Object> p = baseParams();
        p.put(key, value);
        return p;
    }

    private static Object value(SimResult result, String key) {
        return result.outputs().stream().filter(o -> o.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少输出指标: " + key)).value();
    }

    private static double scalar(SimResult result, String key) {
        return ((Number) value(result, key)).doubleValue();
    }

    /** 断言输出的确定性：任何未使用的列表不应为空（防止测试误删断言）。 */
    @Test
    @DisplayName("步骤事件：含公式、ROP、曲线、逐日模拟、汇总五步")
    void stepSequence() {
        SimResult result = engine.run(baseParams(), 42L, null);
        assertEquals(5, result.steps().size(), "EOQ 共 5 个步骤事件");
        assertTrue(result.steps().get(0).message().contains("Q*"), "首步为公式计算");
        assertFalse(result.steps().get(4).message().isBlank(), "末步为汇总");
        assertEquals(1, result.steps().get(0).stepNo());
        assertEquals(5, result.steps().get(4).stepNo());
    }
}
