package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 闭环供应链与逆向物流仿真执行器（T062，CH11-005）。
 * 模型（轻量启发式）：正向流新品年销（annual_sales×1 万件，单价 100 元/成本 60 元）→ 存量市场
 * 中可回收量 = 年销×寿命（product_life）→ 回收率（recovery_rate + 以旧换新激励加成）→ 回收品
 * 分流：可再制造（再制造率 remanufacture_rate，成本=新造成本×成本比，售价=新品价×市场接受度）
 * 与材料回收（回收价值=物料成本×材料回收价值比）→ 未回收部分填埋（填埋成本）→ 闭环利润、
 * 材料循环利用率、废弃物减少量对比，并讨论汽车电池（高回收）vs 消费电子（低回收）差异。
 */
@Component
public class ClosedLoopExecutor implements ScenarioExecutor {

    private static final double NEW_PRICE = 100.0;   // 新品单价（元）
    private static final double NEW_COST = 60.0;     // 新造成本（元）
    private static final double UNIT_WEIGHT = 0.5;   // 单件重量（kg）
    private static final double COLLECT_COST = 8.0;  // 单件回收物流成本（元）

    @Override
    public String engineKey() {
        return "closed-loop";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "annual_sales", 1, 100, errors);
        doubleParam(params, "product_life", 2, 10, errors);
        doubleParam(params, "recovery_rate", 0.1, 0.6, errors);
        doubleParam(params, "remanufacture_rate", 0.3, 0.8, errors);
        Double remanCost = doubleParam(params, "remanufacture_cost_ratio", 0.4, 0.7, errors);
        doubleParam(params, "remanufacture_acceptance", 0.5, 0.95, errors);
        doubleParam(params, "trade_in_incentive", 5, 15, errors);
        doubleParam(params, "material_recovery_value", 10, 30, errors);
        doubleParam(params, "landfill_cost", 1, 20, errors);
        if (errors.isEmpty() && remanCost != null && remanCost < 0.4) {
            errors.add("quality_ok 约束不满足：再制造成本比过低意味着质量妥协，需 ≥ 40% 新造成本");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double annualSalesWan = ((Number) params.get("annual_sales")).doubleValue();
        double life = ((Number) params.get("product_life")).doubleValue();
        double recRate = ((Number) params.get("recovery_rate")).doubleValue();
        double remanRate = ((Number) params.get("remanufacture_rate")).doubleValue();
        double costRatio = ((Number) params.get("remanufacture_cost_ratio")).doubleValue();
        double acceptance = ((Number) params.get("remanufacture_acceptance")).doubleValue();
        double incentive = ((Number) params.get("trade_in_incentive")).doubleValue();
        double materialValue = ((Number) params.get("material_recovery_value")).doubleValue();
        double landfillCost = ((Number) params.get("landfill_cost")).doubleValue();

        double annualUnits = annualSalesWan * 10000;               // 新品年销量（件）
        double marketStock = annualUnits * life;                   // 存量市场（件）
        double theoreticalReturn = marketStock * 0.9;              // 理论可回收（件）

        // 步骤 1：正向流与逆向流规模
        ctx.step(String.format("正向流：新品年销 %.0f 万件（单价 %.0f 元/成本 %.0f 元）；存量市场 %.0f 万件"
                        + "（寿命 %.0f 年）；理论可回收 %.0f 万件",
                annualSalesWan, NEW_PRICE, NEW_COST, marketStock / 10000, life, theoreticalReturn / 10000),
                Map.of("annual_units", round2(annualUnits), "market_stock", round2(marketStock),
                        "theoretical_return", round2(theoreticalReturn)));

        // 步骤 2：回收与激励（以旧换新提升回收率）
        double incentiveBoost = Math.min(0.3, incentive / 100.0 * 2); // 10% 激励 ≈ +0.2 回收率
        double effectiveRec = Math.min(1.0, recRate + incentiveBoost);
        double returned = theoreticalReturn * effectiveRec;
        double incentiveCost = returned * incentive / 100.0 * NEW_PRICE;
        double collectCost = returned * COLLECT_COST;
        ctx.step(String.format("回收率：基础 %.0f%% + 以旧换新激励加成 %.0f%% = 实际 %.0f%%（回收 %.0f 万件）；"
                        + "激励成本 %,.0f 元，回收物流成本 %,.0f 元",
                recRate * 100, incentiveBoost * 100, effectiveRec * 100, returned / 10000,
                incentiveCost, collectCost),
                Map.of("effective_recovery", round2(effectiveRec * 100), "returned_units", round2(returned)));

        // 步骤 3：再制造/材料回收/填埋分流
        double remanUnits = returned * remanRate;                  // 可再制造（件）
        double materialUnits = returned * (1 - remanRate);         // 材料回收池（件）
        double remanPrice = NEW_PRICE * acceptance;                // 再制造品定价（元）
        double remanCost = NEW_COST * costRatio;
        double remanSold = Math.min(remanUnits, annualUnits * acceptance * 0.8); // 市场需求约束
        double remanProfit = remanSold * (remanPrice - remanCost);
        double recoveredValue = materialUnits * materialValue / 100.0 * NEW_COST;
        double landfillUnits = materialUnits * (1 - materialValue / 100.0);
        double landfillTotal = landfillUnits * landfillCost;
        ctx.step(String.format("回收品分流：可再制造 %.0f 万件（成本 %.0f 元=新造 %.0f%%，售价 %.0f 元=新品 %.0f%%）；"
                        + "材料回收价值 %,.0f 元；填埋 %.0f 万件 × %.0f 元 = %,.0f 元",
                remanUnits / 10000, remanCost, costRatio * 100, remanPrice, acceptance * 100,
                recoveredValue, landfillUnits / 10000, landfillCost, landfillTotal),
                Map.of("remanufactured_units", round2(remanUnits), "reman_price", round2(remanPrice),
                        "landfill_units", round2(landfillUnits)));

        // 步骤 4：闭环利润与循环利用指标
        double newProfit = annualUnits * (NEW_PRICE - NEW_COST);
        double closedLoopProfit = newProfit + remanProfit + recoveredValue - collectCost - incentiveCost - landfillTotal;
        double recycled = remanSold + materialUnits * materialValue / 100.0;
        double recycleRate = returned > 0 ? recycled / returned * 100 : 0;
        double wasteReduction = (returned - landfillUnits) * UNIT_WEIGHT / 1000.0; // 吨
        ctx.step(String.format("闭环利润 %,.0f 元 = 新品 %,.0f + 再制造 %,.0f + 材料回收 %,.0f"
                        + " − 回收物流 %,.0f − 激励 %,.0f − 填埋 %,.0f；材料循环利用率 %.1f%%，废弃物减少 %.0f 吨",
                closedLoopProfit, newProfit, remanProfit, recoveredValue, collectCost, incentiveCost,
                landfillTotal, recycleRate, wasteReduction),
                Map.of("closed_loop_profit", round2(closedLoopProfit), "recycle_rate", round2(recycleRate),
                        "waste_reduction", round2(wasteReduction)));

        // 步骤 5：销售结构走势与激励 ROI（讨论汽车电池 vs 消费电子）
        List<Double> x = new ArrayList<>();
        List<Double> remanShare = new ArrayList<>();
        for (int y = 1; y <= 6; y++) {
            x.add((double) y);
            double share = remanSold / (annualUnits + remanSold) * 100 * Math.min(1.0, 0.5 + y * 0.1);
            remanShare.add(round2(Math.min(50, share)));
        }
        double extraReturn = theoreticalReturn * incentiveBoost;
        double extraValue = extraReturn * (remanRate * (remanPrice - remanCost) * 0.6 + materialValue / 100.0 * NEW_COST * 0.4);
        double incentiveRoi = incentiveCost > 0 ? extraValue / incentiveCost * 100 : 0;
        ctx.step(String.format("新品/再制造品销售结构：再制造占比逐年升至 %.1f%%；激励 ROI %.0f%%"
                        + "（每 1 元激励带来 %.0f 元循环价值）。讨论：汽车电池回收率高（贵金属价值大、法规回收体系成熟、"
                        + "专业回收渠道集中），而消费电子回收率低（体积小分散、数据隐私顾虑、翻新认知度低）",
                remanShare.get(5), incentiveRoi, extraValue / Math.max(1, incentiveCost)),
                Map.of("sales_mix_series", series(x, "再制造品占比(%)", remanShare),
                        "incentive_roi", round2(incentiveRoi)));

        // 输出指标（FR-007）
        ctx.output("closed_loop_profit", "闭环总利润", "scalar", round2(closedLoopProfit), "元");
        ctx.output("material_recycle_rate", "材料循环利用率", "gauge", List.of(
                Map.of("name", "材料循环利用率", "value", round2(recycleRate))), "%");
        ctx.output("waste_reduction", "废弃物减少量", "scalar", round2(wasteReduction), "吨");
        ctx.output("sales_mix_series", "新品vs再制造品销售比例", "series",
                series(x, "再制造品占比(%)", remanShare), "%");
        ctx.output("incentive_roi", "回收激励ROI", "scalar", round2(incentiveRoi), "%");
    }
}
