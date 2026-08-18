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

/**
 * 允许缺货的 EOQ 模型仿真执行器（T053，CH2-005；SC-010 公式契约）。
 * 模型（教材算例）：Q* = √(2·C2·D/C1·(C1+C3)/C3)，S* = Q*·C1/(C1+C3)，
 * 最大库存 = Q*−S*；年总成本 = C1(Q−S)²/2Q + C2·D/Q + C3·S²/2Q。
 * 算例 D=10000/C2=100/C1=2/C3=5 → Q*=1183.22、S*=338.06、最大库存=845.16、TC=1690.26。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class StockoutEoqExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "stockout-eoq";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "annual_demand", 100, 100000, errors);
        doubleParam(params, "order_cost", 10, 5000, errors);
        doubleParam(params, "holding_cost", 1, 500, errors);
        doubleParam(params, "backorder_cost", 3, 5000, errors);
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double d = ((Number) params.get("annual_demand")).doubleValue();
        double orderCost = ((Number) params.get("order_cost")).doubleValue();
        double holdingCost = ((Number) params.get("holding_cost")).doubleValue();
        double backorderCost = ((Number) params.get("backorder_cost")).doubleValue();

        // 步骤 1：参数与缺货策略设定
        ctx.step(String.format("参数设定：年需求 D=%.0f、单次订货成本 C2=%.0f、年持有成本 C1=%.0f、年缺货成本 C3=%.0f（缺货补交 backorder）",
                d, orderCost, holdingCost, backorderCost),
                Map.of("annual_demand", round2(d)));

        // 步骤 2：最优订货量与最大缺货量
        double qStar = Math.sqrt(2 * orderCost * d / holdingCost * (holdingCost + backorderCost) / backorderCost);
        double backorder = qStar * holdingCost / (holdingCost + backorderCost);
        double maxInventory = qStar - backorder;
        ctx.step(String.format("最优订货量 Q* = √(2×%.0f×%.0f/%.0f×(%.0f+%.0f)/%.0f) = %.2f 单位；"
                        + "最大缺货量 S* = Q×C1/(C1+C3) = %.2f；最大库存 = Q−S = %.2f",
                orderCost, d, holdingCost, holdingCost, backorderCost, backorderCost, qStar, backorder, maxInventory),
                Map.of("q_star", round2(qStar), "backorder_qty", round2(backorder), "max_inventory", round2(maxInventory)));

        // 步骤 3：年总成本与缺货周期占比
        double totalCost = holdingCost * maxInventory * maxInventory / (2 * qStar)
                + orderCost * d / qStar + backorderCost * backorder * backorder / (2 * qStar);
        double stockoutRatio = backorder / qStar * 100;
        ctx.step(String.format("年总成本 = C1(Q−S)²/2Q + C2D/Q + C3S²/2Q = %.2f 元；缺货周期占比 = S/Q = %.2f%%",
                totalCost, stockoutRatio),
                Map.of("total_cost", round2(totalCost), "stockout_ratio", round2(stockoutRatio)));

        // 步骤 4：与不允许缺货 EOQ 对比
        double qEoq = Math.sqrt(2 * orderCost * d / holdingCost);
        double eoqCost = orderCost * d / qEoq + holdingCost * qEoq / 2;
        List<Map<String, Object>> compare = new ArrayList<>();
        compare.add(Map.of("name", "允许缺货", "value", round2(totalCost)));
        compare.add(Map.of("name", "不允许缺货(EOQ)", "value", round2(eoqCost)));
        String conclusion = totalCost <= eoqCost
                ? "允许缺货降低了总成本（缺货成本 C3=%.0f 低于持有成本对冲收益）"
                : "不允许缺货更经济（缺货成本过高）";
        ctx.step(String.format("对比：允许缺货 %.2f 元 vs 不允许缺货 %.2f 元 → " + conclusion,
                totalCost, eoqCost, backorderCost),
                Map.of("cost_allow", round2(totalCost), "cost_forbid", round2(eoqCost)));

        // 输出指标（FR-007）
        ctx.output("q_star", "最优订货量Q*", "scalar", round2(qStar), "单位");
        ctx.output("backorder_qty", "最大缺货量S*", "scalar", round2(backorder), "单位");
        ctx.output("max_inventory", "最大库存水平", "scalar", round2(maxInventory), "单位");
        ctx.output("total_cost", "年总成本", "scalar", round2(totalCost), "元");
        ctx.output("cost_compare", "缺货vs不缺货总成本对比", "compare", compare, "元");
        ctx.output("stockout_ratio", "缺货周期占比", "scalar", round2(stockoutRatio), "%");
    }
}
