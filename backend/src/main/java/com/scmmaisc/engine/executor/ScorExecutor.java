package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * SCOR 模型五流程仿真执行器（T057，CH6-004）。
 * 模型：Plan/Source/Make/Deliver/Return 五流程串行配置 → 各流程绩效打分（周期/负荷/提前期）
 * → 五维绩效仪表盘（可靠性/响应性/敏捷性/成本/资产管理）→ 瓶颈流程识别（topo）→
 * 完美订单履行率与现金-现金周期（DIO+DSO−DPO）。
 */
@Component
public class ScorExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "scor";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer plan = intParam(params, "plan_cycle", 1, 12, errors);
        Integer suppliers = intParam(params, "supplier_count", 3, 50, errors);
        Double procure = doubleParam(params, "procurement_lead_time", 1, 30, errors);
        Double make = doubleParam(params, "manufacturing_cycle", 1, 30, errors);
        Double util = doubleParam(params, "capacity_utilization", 0.6, 0.95, errors);
        Double deliver = doubleParam(params, "delivery_cycle", 1, 14, errors);
        Double ret = doubleParam(params, "return_cycle", 1, 21, errors);
        if (errors.isEmpty() && plan != null && suppliers != null && procure != null
                && make != null && util != null && deliver != null && ret != null) {
            // 约束 utilization_ok：产能利用率 ≤ 95% 以保持敏捷性
            if (util > 0.95) {
                errors.add("utilization_ok 约束不满足：产能利用率需 ≤ 0.95（过高负荷将牺牲敏捷性）");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double planCycle = ((Number) params.get("plan_cycle")).doubleValue();
        int suppliers = ((Number) params.get("supplier_count")).intValue();
        double procure = ((Number) params.get("procurement_lead_time")).doubleValue();
        double make = ((Number) params.get("manufacturing_cycle")).doubleValue();
        double util = ((Number) params.get("capacity_utilization")).doubleValue();
        double deliver = ((Number) params.get("delivery_cycle")).doubleValue();
        double ret = ((Number) params.get("return_cycle")).doubleValue();

        String[] procNames = {"Plan计划", "Source采购", "Make制造", "Deliver交付", "Return退货"};
        String[] procKeys = {"plan", "source", "make", "deliver", "return"};
        double[] procScores = {
                100 - planCycle * 3,
                100 - procure * 2 - Math.max(0, 20 - suppliers) * 0.5,
                100 - (make * 2 + util * 40),
                100 - deliver * 4,
                100 - ret * 2.5
        };
        int bottleneck = 0;
        for (int i = 1; i < 5; i++) {
            if (procScores[i] < procScores[bottleneck]) {
                bottleneck = i;
            }
        }

        // 步骤 1-5：五流程运行
        ctx.step(String.format("Plan：S&OP 周期 %.0f 周 → 绩效 %.1f", planCycle, procScores[0]),
                Map.of("plan_cycle", planCycle));
        ctx.step(String.format("Source：%d 家供应商，采购提前期 %.0f 天 → 绩效 %.1f",
                        suppliers, procure, procScores[1]), Map.of("supplier_count", suppliers));
        ctx.step(String.format("Make：制造周期 %.0f 天，产能利用率 %.0f%% → 绩效 %.1f",
                        make, util * 100, procScores[2]), Map.of("manufacturing_cycle", make));
        ctx.step(String.format("Deliver：交付周期 %.0f 天 → 绩效 %.1f", deliver, procScores[3]),
                Map.of("delivery_cycle", deliver));
        ctx.step(String.format("Return：退货处理 %.0f 天 → 绩效 %.1f；瓶颈流程 = %s（%.1f 分）",
                        ret, procScores[4], procNames[bottleneck], procScores[bottleneck]),
                Map.of("return_cycle", ret, "bottleneck", procKeys[bottleneck],
                        "bottleneck_score", round2(procScores[bottleneck])));

        // 完美订单履行率（可靠性）
        double onTime = Math.max(0.85, 1 - deliver * 0.01);
        double por = 0.98 * 0.99 * 0.99 * onTime * 100;

        // 现金-现金周期 = DIO + DSO − DPO
        double dio = planCycle * 7 + make + procure * 0.3;
        double dpo = 45 + suppliers * 0.2;
        double c2c = dio + 30 - dpo;

        // 五维绩效仪表盘 + 流程拓扑
        List<Map<String, Object>> dashboard = List.of(
                Map.of("name", "可靠性", "value", round2(por)),
                Map.of("name", "响应性", "value", round2(Math.min(100, 100 - deliver * 5))),
                Map.of("name", "敏捷性", "value", round2(Math.min(100, 100 - util * 50 - make * 1.5))),
                Map.of("name", "成本", "value", round2(Math.min(100, 100 - procure * 1.5 - deliver * 2))),
                Map.of("name", "资产管理", "value", round2(Math.min(100, Math.max(30, 100 - c2c * 0.5)))));
        Map<String, Object> topo = new LinkedHashMap<>();
        topo.put("nodes", List.of(
                Map.of("id", "plan", "name", "Plan 计划", "type", "process"),
                Map.of("id", "source", "name", "Source 采购", "type", "process"),
                Map.of("id", "make", "name", "Make 制造", "type", "process"),
                Map.of("id", "deliver", "name", "Deliver 交付", "type", "process"),
                Map.of("id", "return", "name", "Return 退货", "type", "process")));
        topo.put("edges", List.of(
                Map.of("source", "plan", "target", "source"),
                Map.of("source", "source", "target", "make"),
                Map.of("source", "make", "target", "deliver"),
                Map.of("source", "deliver", "target", "return")));
        ctx.step(String.format("完美订单履行率 %.1f%%；现金-现金周期 %.0f 天（DIO %.0f + DSO 30 − DPO %.0f）",
                        por, c2c, dio, dpo),
                Map.of("perfect_order_rate", round2(por), "cash_to_cash", round2(c2c),
                        "performance_dashboard", dashboard));

        // 输出指标（FR-007）
        ctx.output("performance_dashboard", "五流程绩效仪表盘", "gauge", dashboard, null);
        ctx.output("process_bottleneck", "流程瓶颈识别", "topo", topo, null);
        ctx.output("perfect_order_rate", "完美订单履行率", "scalar", round2(por), "%");
        ctx.output("cash_to_cash", "现金-现金周期", "scalar", round2(c2c), "天");
    }
}
