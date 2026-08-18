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
 * 非瞬时补货(POQ)模型仿真执行器（T053，CH2-004；SC-010 公式契约）。
 * 模型（教材算例）：Qp* = √(2·C2·D/(C1·(1−d/p)))，D = d×365；
 * Imax = Qp·(1−d/p)；年总成本 = C1·Imax/2 + C2·D/Qp；年生产批次 = D/Qp。
 * 算例 d=100/p=200/C2=100/C1=2 → Qp*=2701.85、Imax=1350.93、TC=2701.85、批次=13.51。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class PoqExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "poq";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double d = doubleParam(params, "daily_demand", 10, 1000, errors);
        Double p = doubleParam(params, "production_rate", 10, 2000, errors);
        doubleParam(params, "setup_cost", 10, 5000, errors);
        doubleParam(params, "holding_cost", 1, 500, errors);
        // 约束 p_gt_d：生产率 p 必须大于需求率 d
        if (errors.isEmpty() && d != null && p != null && p <= d) {
            errors.add("p_gt_d 约束不满足：production_rate (" + p + ") 必须大于 daily_demand (" + d + ")");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double d = ((Number) params.get("daily_demand")).doubleValue();
        double p = ((Number) params.get("production_rate")).doubleValue();
        double setupCost = ((Number) params.get("setup_cost")).doubleValue();
        double holdingCost = ((Number) params.get("holding_cost")).doubleValue();
        double annualDemand = d * 365;
        double ratio = 1 - d / p;

        // 步骤 1：需求与速率设定
        ctx.step(String.format("参数设定：日需求率 d=%.0f、日生产率 p=%.0f（p>d ✓）、年需求 D=%.0f、生产准备成本 C2=%.0f、年持有成本 C1=%.0f",
                d, p, annualDemand, setupCost, holdingCost),
                Map.of("annual_demand", round2(annualDemand), "ratio", round2(ratio)));

        // 步骤 2：最优生产批量
        double qStar = Math.sqrt(2 * setupCost * annualDemand / (holdingCost * ratio));
        double imax = qStar * ratio;
        ctx.step(String.format("最优生产批量 Qp* = √(2×%.0f×%.0f/(%.0f×%.2f)) = %.2f 单位；"
                        + "最大库存 Imax = Qp(1-d/p) = %.2f×%.2f = %.2f",
                setupCost, annualDemand, holdingCost, ratio, qStar, qStar, ratio, imax),
                Map.of("q_star", round2(qStar), "imax", round2(imax)));

        // 步骤 3：生产/消耗周期与年批次
        double productionDays = qStar / p;
        double consumptionDays = qStar / d;
        double runs = annualDemand / qStar;
        ctx.step(String.format("生产周期 %.2f 天、消耗周期 %.2f 天（生产占比 %.1f%%）；年生产批次 = D/Qp = %.2f 批",
                productionDays, consumptionDays, d / p * 100, runs),
                Map.of("production_days", round2(productionDays), "consumption_days", round2(consumptionDays),
                        "production_runs", round2(runs)));

        // 步骤 4：年总成本与 EOQ 对比（自产 vs 外购）
        double totalCost = holdingCost * imax / 2 + setupCost * annualDemand / qStar;
        double qEoq = Math.sqrt(2 * setupCost * annualDemand / holdingCost);
        double eoqCost = setupCost * annualDemand / qEoq + holdingCost * qEoq / 2;
        List<Map<String, Object>> costVs = new ArrayList<>();
        costVs.add(Map.of("name", "POQ 自产", "value", round2(totalCost)));
        costVs.add(Map.of("name", "EOQ 外购", "value", round2(eoqCost)));
        String conclusion = totalCost <= eoqCost
                ? "自产批量生产更经济（边际成本随批量摊薄）"
                : "外购订货更经济（非瞬时补货节省有限）";
        ctx.step(String.format("年总成本 = C1×Imax/2 + C2×D/Qp = %.2f 元；EOQ 外购基准 %.2f 元 → %s",
                totalCost, eoqCost, conclusion),
                Map.of("total_cost", round2(totalCost), "eoq_cost", round2(eoqCost)));

        // 输出指标（FR-007）
        ctx.output("q_star", "最优生产批量", "scalar", round2(qStar), "单位");
        ctx.output("imax", "最大库存Imax", "scalar", round2(imax), "单位");
        ctx.output("total_cost", "年总成本", "scalar", round2(totalCost), "元");
        ctx.output("production_runs", "年生产批次", "scalar", round2(runs), "批");
        ctx.output("cycle_share", "生产/消耗周期占比", "gauge",
                List.of(Map.of("name", "生产周期", "value", round2(d / p * 100)),
                        Map.of("name", "消耗周期", "value", round2((1 - d / p) * 100))),
                "%");
        ctx.output("cost_vs_eoq", "年总成本vs EOQ对比", "compare", costVs, "元");
    }
}
