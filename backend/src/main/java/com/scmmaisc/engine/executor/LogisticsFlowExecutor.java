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
 * 供应/生产/销售/逆向物流四环节流转仿真执行器（T052，CH1-003）。
 * 模型：物料守恒（总产量 = 销售 + 退货 − 回收再利用，退货经分拣后一部分再循环利用）；
 * BOM 层级放大供应物流量，退货率驱动逆向物流量，回收再利用率决定回流量；
 * 各环节物流量 × 单位成本 = 环节成本，汇总为物流总成本。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class LogisticsFlowExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "logistics-flow";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "daily_output", 100, 10000, errors);
        intParam(params, "bom_levels", 2, 5, errors);
        doubleParam(params, "return_rate", 0.01, 0.15, errors);
        doubleParam(params, "recycle_rate", 0, 0.8, errors);
        doubleParam(params, "lead_inbound", 0.5, 7, errors);
        doubleParam(params, "lead_production", 0.5, 7, errors);
        doubleParam(params, "lead_outbound", 0.5, 7, errors);
        doubleParam(params, "lead_reverse", 0.5, 7, errors);
        doubleParam(params, "cost_inbound", 0, 100, errors);
        doubleParam(params, "cost_production", 0, 100, errors);
        doubleParam(params, "cost_outbound", 0, 100, errors);
        doubleParam(params, "cost_reverse", 0, 100, errors);
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double dailyOutput = ((Number) params.get("daily_output")).doubleValue();
        int bomLevels = ((Number) params.get("bom_levels")).intValue();
        double returnRate = ((Number) params.get("return_rate")).doubleValue();
        double recycleRate = ((Number) params.get("recycle_rate")).doubleValue();
        double leadIn = ((Number) params.get("lead_inbound")).doubleValue();
        double leadPro = ((Number) params.get("lead_production")).doubleValue();
        double leadOut = ((Number) params.get("lead_outbound")).doubleValue();
        double leadRev = ((Number) params.get("lead_reverse")).doubleValue();
        double costIn = ((Number) params.get("cost_inbound")).doubleValue();
        double costPro = ((Number) params.get("cost_production")).doubleValue();
        double costOut = ((Number) params.get("cost_outbound")).doubleValue();
        double costRev = ((Number) params.get("cost_reverse")).doubleValue();

        // 步骤 1：需求驱动的正向物流量（BOM 层级放大供应物流）
        double inbound = dailyOutput * bomLevels;          // 供应物流：原料按 BOM 层级放大
        double production = dailyOutput * bomLevels;       // 生产物流：车间流转（原料+在制品）
        double sales = dailyOutput * (1 - returnRate);     // 销售物流：实际售出
        double returned = dailyOutput * returnRate;        // 退货量
        ctx.step(String.format("正向物流测算：日产量 %.0f 单位、BOM %d 层 → 供应物流 %.0f、生产物流 %.0f、销售物流 %.0f（退货率 %.0f%%）",
                dailyOutput, bomLevels, inbound, production, sales, returnRate * 100),
                Map.of("inbound_qty", round2(inbound), "production_qty", round2(production), "sales_qty", round2(sales)));

        // 步骤 2：逆向物流与回收再利用（物料守恒）
        double recycle = returned * recycleRate;           // 分拣后回流量（再进入供应链）
        double disposal = returned - recycle;              // 报废处置量
        ctx.step(String.format("逆向物流测算：退货 %.0f 单位（提前期 %.1f 天）→ 分拣 → 回收再利用 %.0f（再利用率 %.0f%%）、报废处置 %.0f",
                returned, leadRev, recycle, recycleRate * 100, disposal),
                Map.of("reverse_qty", round2(returned), "recycle_qty", round2(recycle), "disposal_qty", round2(disposal)));

        // 步骤 3：四环节物流量占比
        double totalQty = inbound + production + sales + returned;
        List<Map<String, Object>> flowShare = new ArrayList<>();
        flowShare.add(Map.of("name", "供应物流", "value", round2(inbound / totalQty * 100)));
        flowShare.add(Map.of("name", "生产物流", "value", round2(production / totalQty * 100)));
        flowShare.add(Map.of("name", "销售物流", "value", round2(sales / totalQty * 100)));
        flowShare.add(Map.of("name", "逆向物流", "value", round2(returned / totalQty * 100)));
        ctx.step(String.format("物流量占比：供应 %.0f%%、生产 %.0f%%、销售 %.0f%%、逆向 %.0f%%",
                inbound / totalQty * 100, production / totalQty * 100, sales / totalQty * 100, returned / totalQty * 100),
                Map.of("total_flow", round2(totalQty)));

        // 步骤 4：环节成本与总成本
        double costInbound = costIn * inbound;
        double costProduction = costPro * production;
        double costOutbound = costOut * sales;
        double costReverse = costRev * returned;
        double totalCost = costInbound + costProduction + costOutbound + costReverse;
        List<Map<String, Object>> costShare = new ArrayList<>();
        costShare.add(Map.of("name", "供应物流", "value", round2(costInbound)));
        costShare.add(Map.of("name", "生产物流", "value", round2(costProduction)));
        costShare.add(Map.of("name", "销售物流", "value", round2(costOutbound)));
        costShare.add(Map.of("name", "逆向物流", "value", round2(costReverse)));
        ctx.step(String.format("物流总成本 %.2f 元/天：供应 %.2f + 生产 %.2f + 销售 %.2f + 逆向 %.2f（提前期 供应%.1f/生产%.1f/销售%.1f/逆向%.1f 天）",
                totalCost, costInbound, costProduction, costOutbound, costReverse,
                leadIn, leadPro, leadOut, leadRev),
                Map.of("total_cost", round2(totalCost), "cost_inbound", round2(costInbound),
                        "cost_production", round2(costProduction), "cost_outbound", round2(costOutbound),
                        "cost_reverse", round2(costReverse)));

        // 步骤 5：闭环流转拓扑
        Map<String, Object> sankey = new LinkedHashMap<>();
        sankey.put("nodes", List.of(
                Map.of("id", "supplier", "name", "供应商", "type", "origin"),
                Map.of("id", "inbound", "name", "供应物流", "type", "logistics"),
                Map.of("id", "production", "name", "生产物流", "type", "logistics"),
                Map.of("id", "outbound", "name", "销售物流", "type", "logistics"),
                Map.of("id", "customer", "name", "客户", "type", "destination"),
                Map.of("id", "reverse", "name", "逆向物流", "type", "logistics"),
                Map.of("id", "sorting", "name", "分拣中心", "type", "processing"),
                Map.of("id", "recycle", "name", "回收再利用", "type", "processing"),
                Map.of("id", "disposal", "name", "报废处置", "type", "processing")));
        sankey.put("edges", List.of(
                Map.of("source", "supplier", "target", "inbound", "value", round2(inbound)),
                Map.of("source", "inbound", "target", "production", "value", round2(inbound)),
                Map.of("source", "production", "target", "outbound", "value", round2(sales + recycle)),
                Map.of("source", "outbound", "target", "customer", "value", round2(sales)),
                Map.of("source", "customer", "target", "reverse", "value", round2(returned)),
                Map.of("source", "reverse", "target", "sorting", "value", round2(returned)),
                Map.of("source", "sorting", "target", "recycle", "value", round2(recycle)),
                Map.of("source", "sorting", "target", "disposal", "value", round2(disposal)),
                Map.of("source", "recycle", "target", "inbound", "value", round2(recycle))));
        ctx.step(String.format("闭环流转拓扑：物料从供应商经四环节流向客户，退货经分拣后 %.0f%% 回流再利用、%.0f%% 报废处置",
                recycleRate * 100, (1 - recycleRate) * 100), Map.of("recycle_rate", round2(recycleRate * 100)));

        // 输出指标（FR-007）
        ctx.output("flow_share", "各环节物流量占比", "compare", flowShare, "%");
        ctx.output("cost_share", "各环节成本构成", "compare", costShare, "元");
        ctx.output("total_cost", "物流总成本", "scalar", round2(totalCost), "元");
        ctx.output("flow_sankey", "物料流转Sankey图", "topo", sankey, null);
    }
}
