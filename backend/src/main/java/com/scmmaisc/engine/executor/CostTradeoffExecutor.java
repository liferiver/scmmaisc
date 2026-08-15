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
 * 物流成本背反（效益背反）动态仿真执行器（T025，CH2-002）。
 * 教材模型：仓库数 n 上升 → 运输成本↓（就近配送）、库存持有成本↑（分散库存，平方根定律）、
 * 缺货成本↓（覆盖更广）；总成本呈 U 形，取最低点即最优仓库数。
 * 确定性模型：无随机调用，seed 无关（FR-008）。
 */
@Component
public class CostTradeoffExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "cost-tradeoff";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer demand = intParam(params, "annual_demand", 10_000, 1_000_000, errors);
        Integer candidates = intParam(params, "warehouse_candidates", 1, 20, errors);
        Double fixed = doubleParam(params, "warehouse_fixed_cost", 50_000, 5_000_000, errors);
        Double holding = doubleParam(params, "holding_cost", 1, 500, errors);
        Double transportRate = doubleParam(params, "transport_rate", 0.1, 10, errors);
        Double stockoutLoss = doubleParam(params, "stockout_loss", 1, 100, errors);
        Double serviceTarget = doubleParam(params, "service_level_target", 0.9, 0.999, errors);
        if (errors.isEmpty() && demand != null && candidates != null
                && fixed != null && holding != null && transportRate != null
                && stockoutLoss != null && serviceTarget != null) {
            // 约束：仓库数至少 1，服务水平需在可行范围内（总成本模型在此区间有效）
            if (serviceTarget > 0.995) {
                errors.add("service_level_target 过高，总成本模型下无法达到，请降低服务水平目标");
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
        int demand = ((Number) params.get("annual_demand")).intValue();
        int candidates = ((Number) params.get("warehouse_candidates")).intValue();
        double fixed = ((Number) params.get("warehouse_fixed_cost")).doubleValue();
        double holding = ((Number) params.get("holding_cost")).doubleValue();
        double transportRate = ((Number) params.get("transport_rate")).doubleValue();
        double stockoutLoss = ((Number) params.get("stockout_loss")).doubleValue();
        double serviceTarget = ((Number) params.get("service_level_target")).doubleValue();

        // 步骤 1：效益背反原理说明
        ctx.step("效益背反（二律背反）：仓库数↑ → 运输成本↓（就近配送）、库存持有成本↑（分散库存）→ 需总成本分析寻找均衡点",
                Map.of("annual_demand", demand, "service_level_target", round2(serviceTarget)));

        // 步骤 2：逐仓库数计算四项成本（1..candidates）
        List<Double> xs = new ArrayList<>();
        List<Double> totalSeries = new ArrayList<>();
        List<Double> transportSeries = new ArrayList<>();
        List<Double> inventorySeries = new ArrayList<>();
        List<Double> stockoutSeries = new ArrayList<>();
        double bestTotal = Double.MAX_VALUE;
        int bestN = 1;
        for (int n = 1; n <= candidates; n++) {
            double transport = transportRate * demand / Math.sqrt(n);          // 就近配送：随 n 递减
            double inventory = holding * demand / Math.sqrt(n) * 0.5;          // 平方根定律：分散库存上升
            double stockout = stockoutLoss * demand * (1 - serviceTarget) / Math.sqrt(n);
            double total = fixed * n + transport + inventory + stockout;       // U 形总成本
            xs.add((double) n);
            totalSeries.add(round2(total));
            transportSeries.add(round2(transport));
            inventorySeries.add(round2(inventory));
            stockoutSeries.add(round2(stockout));
            if (total < bestTotal) {
                bestTotal = total;
                bestN = n;
            }
        }
        ctx.step(String.format("逐仓库数测算：运输成本与库存成本反向变动，总成本在 %d 个仓库处最低（%.0f 元）",
                bestN, bestTotal), Map.of("best_n", bestN, "best_total", round2(bestTotal)));

        // 步骤 3：总成本 U 形曲线
        Map<String, Object> curve = new LinkedHashMap<>();
        curve.put("x", xs);
        curve.put("series", List.of(
                Map.of("name", "总成本", "data", totalSeries),
                Map.of("name", "运输成本", "data", transportSeries),
                Map.of("name", "库存持有成本", "data", inventorySeries),
                Map.of("name", "缺货成本", "data", stockoutSeries)));
        ctx.step(String.format("总成本 U 形曲线：运输/库存/缺货成本随仓库数变化，最低点 n* = %d", bestN), curve);

        // 步骤 4：最优仓库数下的成本构成
        double transport = transportRate * demand / Math.sqrt(bestN);
        double inventory = holding * demand / Math.sqrt(bestN) * 0.5;
        double stockout = stockoutLoss * demand * (1 - serviceTarget) / Math.sqrt(bestN);
        double total = fixed * bestN + transport + inventory + stockout;
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("warehouse_fixed", round2(fixed * bestN));
        structure.put("transport", round2(transport));
        structure.put("inventory", round2(inventory));
        structure.put("stockout", round2(stockout));
        structure.put("total", round2(total));
        structure.put("best_n", bestN);
        ctx.step(String.format("最优方案（n=%d）：固定成本 %.0f 元、运输 %.0f 元、库存 %.0f 元、缺货 %.0f 元",
                bestN, fixed * bestN, transport, inventory, stockout), structure);

        // 步骤 5：冰山下成本提示
        ctx.step("冰山理论：显性成本（运输/仓储）之外，缺货与响应成本是隐性成本——总成本分析才能看到全貌",
                Map.of("hidden_cost_share", round2((stockout + inventory) / total * 100)));

        // 输出指标（FR-007，声明顺序与 T024 JSON 一致）
        ctx.output("tradeoff_curve", "运输 vs 库存背反曲线", "compare",
                List.of(
                        Map.of("name", "最优运输成本", "value", round2(transport)),
                        Map.of("name", "最优库存成本", "value", round2(inventory)),
                        Map.of("name", "最优缺货成本", "value", round2(stockout))),
                "元");
        ctx.output("total_cost_curve", "总成本 U 形曲线", "series", curve, "元");
        ctx.output("optimal_warehouses", "最优仓库数", "scalar", bestN, "个");
        ctx.output("cost_structure", "最优成本构成", "compare",
                List.of(
                        Map.of("name", "仓库固定成本", "value", round2(fixed * bestN)),
                        Map.of("name", "运输成本", "value", round2(transport)),
                        Map.of("name", "库存持有成本", "value", round2(inventory)),
                        Map.of("name", "缺货成本", "value", round2(stockout))),
                "元");
    }
}
