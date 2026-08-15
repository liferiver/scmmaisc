package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 批发价格合同与双重边际效应仿真执行器（T026，CH8-004）。
 * 教材模型：线性需求 D(p) = a - b·p；Stackelberg 博弈 —— 供应商（领导者）给定批发价 w，
 * 零售商（跟随者）给定零售价 p 后按需求量订货 q = D(p)。
 * 分散决策（双方各自加价）总利润 &lt; 集中决策总利润 → 双重边际效率损失。
 */
@Component
public class ContractExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "contract";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double a = doubleParam(params, "market_size_a", 100, 1000, errors);
        Double b = doubleParam(params, "price_sensitivity_b", 1, 10, errors);
        Double c = doubleParam(params, "unit_cost_c", 10, 100, errors);
        Double p = doubleParam(params, "retail_price_p", 10, 200, errors);
        Double w = doubleParam(params, "wholesale_price_w", 10, 200, errors);
        if (errors.isEmpty() && a != null && b != null && c != null && p != null && w != null) {
            if (w <= c) {
                errors.add("wholesale_price_w 必须高于 unit_cost_c（w > c，供应商才有利可图）");
            }
            if (p <= w) {
                errors.add("retail_price_p 必须高于 wholesale_price_w（p > w，零售商才有利可图）");
            }
            if (a - b * p <= 0) {
                errors.add(String.format("零售价 %.0f 过高，需求 D(p)=a-bp=%.0f ≤ 0，市场无需求", p, a - b * p));
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
        double a = ((Number) params.get("market_size_a")).doubleValue();
        double b = ((Number) params.get("price_sensitivity_b")).doubleValue();
        double c = ((Number) params.get("unit_cost_c")).doubleValue();
        double p = ((Number) params.get("retail_price_p")).doubleValue();
        double w = ((Number) params.get("wholesale_price_w")).doubleValue();

        // 步骤 1：需求函数与决策结构
        double q = Math.max(0, a - b * p);
        ctx.step(String.format("需求函数 D(p) = %.0f - %.0f·p：给定零售价 p=%.0f → 需求量 q=%.0f",
                a, b, p, q), Map.of("demand", round2(q)));

        // 步骤 2：零售商利润（跟随者）
        double retailerProfit = (p - w) * q;
        ctx.step(String.format("零售商利润 = (p - w)·q = (%.0f - %.0f)×%.0f = %.0f 元",
                p, w, q, retailerProfit), Map.of("retailer_profit", round2(retailerProfit)));

        // 步骤 3：供应商利润（领导者）
        double supplierProfit = (w - c) * q;
        ctx.step(String.format("供应商利润 = (w - c)·q = (%.0f - %.0f)×%.0f = %.0f 元",
                w, c, q, supplierProfit), Map.of("supplier_profit", round2(supplierProfit)));

        // 步骤 4：双重边际效率损失（对比集中决策）
        double pCentral = (a + b * c) / (2 * b);          // 集中决策最优零售价
        double qCentral = Math.max(0, a - b * pCentral);
        double totalCentral = (pCentral - c) * qCentral;
        double totalDecentral = (p - c) * q;
        double efficiencyLoss = totalCentral > 0 ? (1 - totalDecentral / totalCentral) * 100 : 0;
        ctx.step(String.format("集中决策：p*=%.0f，q*=%.0f，总利润 %.0f 元；分散决策总利润 %.0f 元 → 效率损失 %.1f%%",
                pCentral, qCentral, totalCentral, totalDecentral, efficiencyLoss),
                Map.of("p_central", round2(pCentral), "q_central", round2(qCentral),
                        "total_central", round2(totalCentral), "total_decentral", round2(totalDecentral),
                        "efficiency_loss", round2(efficiencyLoss)));

        // 步骤 5：博弈结论
        ctx.step("双重边际（Double Marginalization）：上下游各自加价导致总利润低于集中决策，"
                + "需通过协调合同（回购/收益共享等）消除效率损失",
                Map.of("efficiency_loss", round2(efficiencyLoss)));

        // 输出指标（FR-007）
        ctx.output("supplier_profit", "供应商利润", "scalar", round2(supplierProfit), "元");
        ctx.output("retailer_profit", "零售商利润", "scalar", round2(retailerProfit), "元");
        ctx.output("total_profit", "供应链总利润（分散）", "scalar", round2(totalDecentral), "元");
        ctx.output("efficiency_loss", "双重边际效率损失", "scalar", round2(efficiencyLoss), "%");
        ctx.output("centralized_vs_decentralized", "集中 vs 分散对比", "compare",
                List.of(
                        Map.of("name", "集中决策总利润", "value", round2(totalCentral)),
                        Map.of("name", "分散决策总利润", "value", round2(totalDecentral)),
                        Map.of("name", "效率损失", "value", round2(efficiencyLoss))),
                "元");
    }
}
