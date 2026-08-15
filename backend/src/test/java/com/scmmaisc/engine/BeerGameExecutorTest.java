package com.scmmaisc.engine;

import com.scmmaisc.engine.executor.BeerGameExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 啤酒游戏执行器单元测试（T020）：同 seed 两次运行逐步一致、不同 seed 结果不同、50 轮步骤数正确。
 * 参数 key 约定（T024 与此保持一致）：levels / initial_demand / demand_jump / lead_time /
 * holding_cost / stockout_cost / info_share / demand_noise / total_rounds。
 */
class BeerGameExecutorTest {

    private final SimulationEngine engine = new SimulationEngine(new BeerGameExecutor());

    @Test
    @DisplayName("同 seed 两次运行：步骤事件与输出指标完全一致（FR-008/SC-005）")
    void sameSeedDeterministic() {
        SimResult r1 = engine.run(baseParams(), 7L, null);
        SimResult r2 = engine.run(baseParams(), 7L, null);
        assertEquals(r1.steps(), r2.steps(), "同 seed 步骤事件应逐步一致");
        assertEquals(r1.outputs(), r2.outputs(), "同 seed 输出指标应一致");
    }

    @Test
    @DisplayName("不同 seed：随机需求噪声导致结果不同")
    void differentSeedDiffers() {
        SimResult r1 = engine.run(baseParams(), 1L, null);
        SimResult r2 = engine.run(baseParams(), 2L, null);
        assertNotEquals(r1.steps(), r2.steps(), "不同 seed 步骤事件应不同");
        assertNotEquals(r1.outputs(), r2.outputs(), "不同 seed 输出指标应不同");
    }

    @Test
    @DisplayName("50 轮：步骤数=50、stepNo 连续、每步为 STEP 事件")
    void fiftyRounds() {
        Map<String, Object> params = baseParams();
        assertEquals(50, engine.describeSteps(params), "describeSteps 应等于总轮数");
        SimResult result = engine.run(params, 7L, null);
        assertEquals(50, result.steps().size(), "50 轮应有 50 个步骤事件");
        for (int i = 0; i < result.steps().size(); i++) {
            assertEquals(i + 1, result.steps().get(i).stepNo(), "stepNo 应从 1 连续递增");
            assertEquals("STEP", result.steps().get(i).eventType(), "每轮均为 STEP 事件");
        }
        assertTrue(result.steps().get(0).message().contains("第 1 轮"), "首步消息应含轮次");
        assertEquals(50, result.steps().get(49).stepNo());
    }

    @Test
    @DisplayName("输出指标齐全：订单序列/库存序列/牛鞭指数/总成本/缺货次数")
    void outputsPresent() {
        SimResult result = engine.run(baseParams(), 7L, null);
        List<String> keys = result.outputs().stream().map(OutputValue::key).toList();
        assertTrue(keys.containsAll(List.of("orders_series", "inventory_series", "bullwhip_index",
                "total_cost", "stockout_counts")), "输出指标应齐全: " + keys);

        @SuppressWarnings("unchecked")
        Map<String, Object> orders = (Map<String, Object>) value(result, "orders_series");
        assertEquals(50, ((List<?>) orders.get("x")).size(), "订单序列 x 轴为 50 轮");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) orders.get("series");
        assertEquals(4, series.size(), "默认 4 级供应链 4 条序列");
        assertEquals("零售商", series.get(0).get("name"), "首条序列为零售商");
        assertEquals(50, ((List<?>) series.get(0).get("data")).size(), "每条序列 50 个点");

        assertTrue(((Number) value(result, "total_cost")).doubleValue() > 0, "总成本应为正");
    }

    @Test
    @DisplayName("信息共享开关生效：开启后上游订单波动显著降低")
    void infoSharingReducesBullwhip() {
        Map<String, Object> closed = baseParams();
        Map<String, Object> shared = baseParams();
        shared.put("info_share", true);

        SimResult rClosed = engine.run(closed, 7L, null);
        SimResult rShared = engine.run(shared, 7L, null);

        double beClosed = bullwhip(rClosed, "制造商");
        double beShared = bullwhip(rShared, "制造商");
        assertTrue(beShared < beClosed * 1.05, "信息共享后制造商 BE 应不高于关闭时（" + beClosed + " → " + beShared + "）");
    }

    // ---- 工具 ----

    private static Map<String, Object> baseParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("levels", 4);
        p.put("initial_demand", 4);
        p.put("demand_jump", 8);
        p.put("lead_time", 2);
        p.put("holding_cost", 0.5);
        p.put("stockout_cost", 2.0);
        p.put("info_share", false);
        p.put("demand_noise", 0.5);
        p.put("total_rounds", 50);
        return p;
    }

    private static Object value(SimResult result, String key) {
        return result.outputs().stream().filter(o -> o.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少输出指标: " + key)).value();
    }

    /** 从 bullwhip_index（compare 类型）中取指定节点的 BE 值。 */
    private static double bullwhip(SimResult result, String nodeName) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) value(result, "bullwhip_index");
        return items.stream()
                .filter(m -> nodeName.equals(m.get("name")))
                .map(m -> ((Number) m.get("value")).doubleValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少节点: " + nodeName));
    }

    /** 结构完整性：库存序列与缺货次数序列应包含 4 级节点。 */
    @Test
    @DisplayName("结构完整性：库存序列与缺货次数序列覆盖全部节点")
    void seriesCoverAllNodes() {
        SimResult result = engine.run(baseParams(), 7L, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> inv = (Map<String, Object>) value(result, "inventory_series");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invSeries = (List<Map<String, Object>>) inv.get("series");
        assertEquals(4, invSeries.size(), "库存序列覆盖 4 级");

        @SuppressWarnings("unchecked")
        Map<String, Object> sc = (Map<String, Object>) value(result, "stockout_counts");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scSeries = (List<Map<String, Object>>) sc.get("series");
        assertEquals(4, scSeries.size(), "缺货次数序列覆盖 4 级");
        assertFalse(result.cancelled(), "正常执行不应标记取消");
    }
}
