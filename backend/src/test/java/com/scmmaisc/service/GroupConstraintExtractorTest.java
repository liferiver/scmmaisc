package com.scmmaisc.service;

import com.scmmaisc.service.GroupConstraintExtractor.GroupConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语义化标量组约束提取器单元测试（T066，V11）：纯求和比较提取、右端参数/常量、
 * 复杂形态（系数/函数/链式/中文占位/未知标识符）跳过。
 */
class GroupConstraintExtractorTest {

    private static final Set<String> PARAMS = Set.of(
            "weight_tech", "weight_quality", "weight_response", "weight_delivery", "weight_cost",
            "weight_environment", "budget", "info_coverage", "flexibility", "q");

    private static Map<String, Object> c(String name, String expr, String message) {
        return Map.of("name", name, "expression", expr, "message", message);
    }

    @Test
    @DisplayName("提取 weight_* 求和等于常量（CH1-001 六维权重和 = 1）")
    void extractsWeightSumEqualsOne() {
        List<GroupConstraint> groups = GroupConstraintExtractor.extract(List.of(c("weight_sum",
                "weight_tech + weight_quality + weight_response + weight_delivery + weight_cost + weight_environment == 1",
                "评估维度权重和必须等于 1")), PARAMS);
        assertEquals(1, groups.size());
        GroupConstraint g = groups.get(0);
        assertEquals("weight_sum", g.name());
        assertEquals("==", g.op());
        assertEquals(1.0, g.target());
        assertEquals(null, g.targetParam());
        assertEquals(List.of("weight_tech", "weight_quality", "weight_response",
                "weight_delivery", "weight_cost", "weight_environment"), g.params());
    }

    @Test
    @DisplayName("提取求和小于等于常量（CH11-007 物资权重 ≤ 1）与右端参数（CH3-008 成本 ≤ budget）")
    void extractsLeConstantAndParamRhs() {
        List<GroupConstraint> groups = GroupConstraintExtractor.extract(List.of(
                c("weight_sum_ok", "weight_medicine + weight_water + weight_food + weight_tent <= 1", "权重和需 ≤ 1"),
                c("budget_ok", "cost_barcode + cost_rfid + cost_gps + cost_edi + cost_agv + cost_wms <= budget", "成本合计需 ≤ 预算")),
                Set.of("weight_medicine", "weight_water", "weight_food", "weight_tent",
                        "cost_barcode", "cost_rfid", "cost_gps", "cost_edi", "cost_agv", "cost_wms", "budget"));
        assertEquals(2, groups.size());
        assertEquals("weight_sum_ok", groups.get(0).name());
        assertEquals("<=", groups.get(0).op());
        assertEquals(1.0, groups.get(0).target());
        assertEquals("budget_ok", groups.get(1).name());
        assertEquals("<=", groups.get(1).op());
        assertEquals(null, groups.get(1).target());
        assertEquals("budget", groups.get(1).targetParam());
    }

    @Test
    @DisplayName("提取求和大于等于常量（CH4-001 信息化+柔性 ≥ 0.8）")
    void extractsGeConstant() {
        List<GroupConstraint> groups = GroupConstraintExtractor.extract(List.of(
                c("info_flex_ok", "info_coverage + flexibility >= 0.8", "信息化与柔性合计需达标")), PARAMS);
        assertEquals(1, groups.size());
        assertEquals(">=", groups.get(0).op());
        assertEquals(0.8, groups.get(0).target());
        assertEquals(List.of("info_coverage", "flexibility"), groups.get(0).params());
    }

    @Test
    @DisplayName("复杂形态跳过：系数、count()、链式比较、中文占位、未知标识符、单参数比较")
    void skipsComplexShapes() {
        List<Map<String, Object>> constraints = List.of(
                c("fx", "fx_hedge_ratio + backup_options * 0.2 + insurance_ratio >= 0.6", "含系数"),
                c("cnt", "count(r_rate >= 0.95) >= 5", "含函数"),
                c("chain", "0.5*Q* <= Q <= 1.5*Q*", "链式比较"),
                c("zh", "each_s >= 最低可接受阈值", "中文占位"),
                c("unknown", "a + b == 1", "求和项非参数"),
                c("single", "q >= 0", "单参数比较非求和"),
                c("bad", "not-an-expression", "不可解析"));
        List<GroupConstraint> groups = GroupConstraintExtractor.extract(constraints, PARAMS);
        assertTrue(groups.isEmpty(), "复杂/非法形态不应被提取: " + groups);
    }

    @Test
    @DisplayName("空约束 / null 约束返回空列表")
    void emptyInputs() {
        assertTrue(GroupConstraintExtractor.extract(null, PARAMS).isEmpty());
        assertTrue(GroupConstraintExtractor.extract(List.of(), PARAMS).isEmpty());
        assertTrue(GroupConstraintExtractor.extract(
                List.of(Map.of("name", "x")), PARAMS).isEmpty());
    }
}
