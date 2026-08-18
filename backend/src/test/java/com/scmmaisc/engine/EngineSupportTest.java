package com.scmmaisc.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎支撑组件测试（T045）：StepAggregator 子模型聚合（R-13）与随机源可复现性（R-05）。
 * 综合场景（CH5-008/CH7-008/CH8-008/CH9-006）依赖本组件保持确定性（FR-008/SC-005）。
 */
class EngineSupportTest {

    /** 测试桩子模型：输出随机数（证明种子派生）与 3 个步骤事件 + 1 个输出指标。 */
    private static class StubExecutor implements ScenarioExecutor {
        @Override
        public String engineKey() {
            return "stub";
        }

        @Override
        public List<String> validate(Map<String, Object> params) {
            return List.of();
        }

        @Override
        public void run(Map<String, Object> params, SimContext ctx) {
            double g = ctx.random().nextGaussian();
            ctx.step("桩步骤1：随机值 " + g, Map.of("g1", g));
            ctx.step("桩步骤2", Map.of("x", params.getOrDefault("x", 0)));
            ctx.step("桩步骤3", Map.of());
            ctx.output("stub_value", "桩输出", "scalar", g, null);
        }
    }

    @Test
    @DisplayName("子模型种子派生：确定性且跨阶段互异")
    void childSeedDerivation() {
        long s1 = StepAggregator.childSeed(42L, 1);
        long s1b = StepAggregator.childSeed(42L, 1);
        long s2 = StepAggregator.childSeed(42L, 2);
        assertEquals(s1, s1b, "相同 baseSeed+stage 派生种子必须一致");
        assertNotEquals(s1, s2, "不同 stage 派生种子必须不同");
        assertNotEquals(s1, 42L, "派生种子不得等于 baseSeed");
    }

    @Test
    @DisplayName("聚合输出：1 个聚合事件、含子步骤数与全量明细")
    void aggregateEmitsSingleEvent() {
        SimContext child = StepAggregator.runSubModel(new StubExecutor(), params(10), 7L, 1);
        SimContext parent = new SimContext(new LinkedHashMap<>(), 7L);

        StepAggregator.aggregate(parent, 1, "第 1 步：桩子模型", "stub", child);

        assertEquals(1, parent.steps().size(), "父上下文只应收到 1 个聚合事件");
        StepEvent event = parent.steps().get(0);
        assertEquals("STEP", event.eventType());
        assertTrue(event.message().contains("第 1 步"), "事件消息应含阶段名: " + event.message());
        assertTrue(event.message().contains("3"), "事件消息应含子步骤数: " + event.message());

        Map<String, Object> data = event.data();
        assertEquals("stub", data.get("model"));
        assertEquals(1, data.get("stage_no"));
        assertEquals(3, data.get("child_step_count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> detail = (List<Map<String, Object>>) data.get("steps");
        assertEquals(3, detail.size(), "明细应保留全部子步骤事件");
        assertEquals("桩步骤1：随机值", ((String) detail.get(0).get("message")).substring(0, 8));
        assertEquals(1, detail.get(0).get("step_no"));
    }

    @Test
    @DisplayName("子模型自身事件不被聚合破坏（上下文隔离）")
    void childContextUntouched() {
        SimContext child = StepAggregator.runSubModel(new StubExecutor(), params(10), 7L, 1);
        List<StepEvent> before = child.steps();
        SimContext parent = new SimContext(new LinkedHashMap<>(), 7L);
        StepAggregator.aggregate(parent, 1, "阶段", "stub", child);
        assertEquals(before, child.steps(), "聚合后子上下文事件流必须保持不变");
    }

    @Test
    @DisplayName("输出合并：key 加阶段前缀、label 加阶段标注，跨阶段不冲突")
    void mergeOutputsPrefixesKeys() {
        SimContext parent = new SimContext(new LinkedHashMap<>(), 7L);
        SimContext child1 = StepAggregator.runSubModel(new StubExecutor(), params(10), 7L, 1);
        SimContext child2 = StepAggregator.runSubModel(new StubExecutor(), params(10), 7L, 2);

        StepAggregator.mergeOutputs(parent, child1, 1);
        StepAggregator.mergeOutputs(parent, child2, 2);

        List<OutputValue> outs = parent.outputs();
        assertEquals(2, outs.size(), "两个阶段各并入 1 个输出");
        assertEquals("s1_stub_value", outs.get(0).key());
        assertEquals("s2_stub_value", outs.get(1).key());
        assertEquals("阶段1·桩输出", outs.get(0).label());
        assertEquals("阶段2·桩输出", outs.get(1).label());
    }

    @Test
    @DisplayName("同 seed 综合场景可复现：子模型随机序列完全一致")
    void comprehensiveReproducible() {
        SimContext c1 = StepAggregator.runSubModel(new StubExecutor(), params(10), 123L, 1);
        SimContext c2 = StepAggregator.runSubModel(new StubExecutor(), params(10), 123L, 1);
        assertEquals(c1.steps(), c2.steps(), "相同 seed 子模型步骤事件必须一致");
        assertEquals(c1.outputs(), c2.outputs(), "相同 seed 子模型输出必须一致");
    }

    @Test
    @DisplayName("RandomSource 可复现性：相同 seed 相同调用序列结果一致（R-05）")
    void randomSourceReproducible() {
        RandomSource r1 = new RandomSource(2024L);
        RandomSource r2 = new RandomSource(2024L);
        double a = r1.nextGaussian();
        int b = r1.nextInt(100);
        boolean c = r1.nextBoolean();
        assertEquals(a, r2.nextGaussian(), 1e-12);
        assertEquals(b, r2.nextInt(100));
        assertEquals(c, r2.nextBoolean());
    }

    private static Map<String, Object> params(int x) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", x);
        return p;
    }
}
