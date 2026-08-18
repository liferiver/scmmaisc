package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 越库配送与直接转运仿真执行器（T058，CH7-006）。
 * 模型：供应商-门店需求（可传门店日需求矩阵）→ 三种策略日成本对比：
 * 直运（供应商直达门店，零担高运价/无库存）/ 越库（供应商整车入 CD，合并装载整车出 CD，
 * 处理费/极低库存）/ 入仓（整车入仓，整车配送，库存持有成本）；对比装载率与库存水平，
 * 推荐最优策略（沃尔玛越库模式）。
 */
@Component
public class CrossDockingExecutor implements ScenarioExecutor {

    private static final double TON_PER_UNIT = 0.01; // 每件货重（吨）

    @Override
    public String engineKey() {
        return "cross-docking";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "supplier_count", 5, 50, errors);
        intParam(params, "store_count", 10, 200, errors);
        intParam(params, "crossdock_candidates", 1, 10, errors);
        doubleParam(params, "full_truck_rate", 1, 10, errors);
        doubleParam(params, "ltl_rate", 3, 20, errors);
        Double hours = doubleParam(params, "crossdock_hours", 2, 24, errors);
        doubleParam(params, "crossdock_cost", 10, 100, errors);
        doubleParam(params, "holding_cost", 1, 100, errors);
        if (params.get("store_demand") != null) {
            matrixParam(params, "store_demand", 1, 200, 1, 1, 10000, errors);
        }
        if (errors.isEmpty() && hours != null && hours >= 24) {
            errors.add("crossdock_time_ok 约束不满足：越库停留需小于 24 小时（保证门店不断货）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int suppliers = ((Number) params.get("supplier_count")).intValue();
        int stores = ((Number) params.get("store_count")).intValue();
        int candidates = ((Number) params.get("crossdock_candidates")).intValue();
        double fullRate = ((Number) params.get("full_truck_rate")).doubleValue();
        double ltlRate = ((Number) params.get("ltl_rate")).doubleValue();
        double cdHours = ((Number) params.get("crossdock_hours")).doubleValue();
        double cdCost = ((Number) params.get("crossdock_cost")).doubleValue();
        double holdCost = ((Number) params.get("holding_cost")).doubleValue();

        // 步骤 1：门店需求载入（矩阵缺省时按 seed 生成 20-200 件/天）
        double[] demand = new double[stores];
        double[][] demandRaw = matrixParam(params, "store_demand", 1, 200, 1, 1, 10000, new ArrayList<>());
        if (demandRaw != null) {
            for (int i = 0; i < stores; i++) {
                demand[i] = demandRaw[0][i];
            }
        } else {
            for (int i = 0; i < stores; i++) {
                demand[i] = 20 + ctx.random().nextDouble() * 180;
            }
        }
        double totalUnits = 0;
        for (double d : demand) {
            totalUnits += d;
        }
        double tonnage = totalUnits * TON_PER_UNIT;
        ctx.step(String.format("载入 %d 家门店日需求，合计 %,.0f 件（%,.1f 吨/天），供应商 %d 家",
                        stores, totalUnits, tonnage, suppliers),
                Map.of("store_count", stores, "total_demand", round2(totalUnits)));

        // 步骤 2：直运策略（供应商→门店零担，300km，无库存）
        double directCost = tonnage * ltlRate * 300;
        ctx.step(String.format("直运策略：零担 %,.0f 吨×%.1f 元/吨公里×300km = %,.0f 元/天（装载率低/无库存）",
                        tonnage, ltlRate, directCost),
                Map.of("direct_cost", round2(directCost)));

        // 步骤 3：越库策略（供应商→CD 整车 150km + 合并装载 CD→门店整车 + 处理费）
        double cdDist = Math.max(80, 150 - candidates * 5);
        double cdCost1 = tonnage * fullRate * 150;
        double cdCost2 = tonnage * fullRate * cdDist;
        double cdHandle = tonnage * cdCost;
        double crossdockTotal = cdCost1 + cdCost2 + cdHandle;
        ctx.step(String.format("越库策略：整车入 %,.0f 元 + 合并整车出 %,.0f 元 + 处理 %,.0f 元 = %,.0f 元/天（停留 %.0f 小时）",
                        cdCost1, cdCost2, cdHandle, crossdockTotal, cdHours),
                Map.of("crossdock_cost", round2(crossdockTotal)));

        // 步骤 4：入仓策略（整车入仓 200km + 整车配送 150km + 库存持有 21 天）
        double whTransport = tonnage * fullRate * 200 + tonnage * fullRate * 150;
        double inventory = 21 * totalUnits;
        double holding = inventory * holdCost / 365;
        double whTotal = whTransport + holding;
        ctx.step(String.format("入仓策略：运输 %,.0f 元 + 持有 %,.0f 元（%s 件×%.0f 元/件·年/365）= %,.0f 元/天",
                        whTransport, holding, String.format("%,.0f", inventory), holdCost, whTotal),
                Map.of("warehouse_cost", round2(whTotal)));

        // 步骤 5：三策略对比与最优推荐
        List<Map<String, Object>> costCompare = List.of(
                Map.of("name", "直运", "value", round2(directCost)),
                Map.of("name", "越库", "value", round2(crossdockTotal)),
                Map.of("name", "入仓", "value", round2(whTotal)));
        List<Map<String, Object>> loadCompare = List.of(
                Map.of("name", "直运", "value", 45.0),
                Map.of("name", "越库", "value", 85.0),
                Map.of("name", "入仓", "value", 90.0));
        List<Map<String, Object>> invCompare = List.of(
                Map.of("name", "直运", "value", 0.0),
                Map.of("name", "越库", "value", round2(0.2 * totalUnits)),
                Map.of("name", "入仓", "value", round2(inventory)));
        String best;
        double bestCost;
        if (crossdockTotal <= directCost && crossdockTotal <= whTotal) {
            best = "越库配送";
            bestCost = crossdockTotal;
        } else if (whTotal <= directCost) {
            best = "仓储配送";
            bestCost = whTotal;
        } else {
            best = "直接运输";
            bestCost = directCost;
        }
        double saveVsDirect = (directCost - bestCost) / directCost * 100;
        String bestText = String.format("推荐 %s：日成本 %,.0f 元，较直运节省 %,.1f%%，装载率提升、库存极低",
                best, bestCost, Math.max(0, saveVsDirect));
        ctx.step(bestText, Map.of("best_strategy", bestText, "total_cost_compare", costCompare));

        // 输出指标（FR-007）
        ctx.output("total_cost_compare", "三种策略总成本对比", "compare", costCompare, "元");
        ctx.output("loading_rate", "运输效率(装载率)", "compare", loadCompare, "%");
        ctx.output("inventory_level", "库存水平", "compare", invCompare, "件");
        ctx.output("best_strategy", "最优策略推荐", "scalar", bestText, null);
    }
}
