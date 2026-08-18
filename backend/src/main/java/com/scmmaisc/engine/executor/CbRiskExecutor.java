package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 跨境物流风险应对仿真执行器（T056，CH5-005）。
 * 模型：单次货值 1000 万元，三类风险——汇率波动（对冲覆盖率降低敞口）、政治风险（制裁/禁运，
 * 备选路线与保险对冲）、自然风险（港口关闭/天气，保险对冲）；期望损失 + 内存 Monte Carlo
 * 求 95% VaR（5000 次抽样，seed 派生，FR-008 可复现）；恢复时间分布（dist 输出）。
 */
@Component
public class CbRiskExecutor implements ScenarioExecutor {

    private static final double CARGO_VALUE = 1000.0; // 万元

    @Override
    public String engineKey() {
        return "cb-risk";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double fxVol = doubleParam(params, "fx_volatility", 0.05, 0.2, errors);
        Double polProb = doubleParam(params, "political_risk_prob", 0.01, 0.1, errors);
        Double natProb = doubleParam(params, "natural_risk_prob", 0.01, 0.05, errors);
        Double hedge = doubleParam(params, "fx_hedge_ratio", 0, 1.0, errors);
        Integer backup = intParam(params, "backup_options", 0, 3, errors);
        Double insurance = doubleParam(params, "insurance_ratio", 0, 1.0, errors);
        if (errors.isEmpty() && fxVol != null && polProb != null && natProb != null
                && hedge != null && backup != null && insurance != null) {
            // 约束 risk_budget_ok：对冲/备选/保险综合防护水平 ≥ 0.6
            if (hedge + backup * 0.2 + insurance < 0.6) {
                errors.add("risk_budget_ok 约束不满足：综合防护水平（对冲+备选×0.2+保险）需 ≥ 0.6");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 策略总期望损失（万元）：汇率(必然发生) + 政治 + 自然。 */
    private double expectedLoss(double hedge, int backup, double insurance,
                                double fxVol, double polProb, double natProb) {
        double fx = CARGO_VALUE * fxVol * (1 - hedge);
        double pol = CARGO_VALUE * 0.4 * polProb * Math.max(0, 1 - backup * 0.15 - insurance * 0.15);
        double nat = CARGO_VALUE * 0.3 * natProb * Math.max(0, 1 - insurance * 0.8 - backup * 0.1);
        return fx + pol + nat;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double fxVol = ((Number) params.get("fx_volatility")).doubleValue();
        double polProb = ((Number) params.get("political_risk_prob")).doubleValue();
        double natProb = ((Number) params.get("natural_risk_prob")).doubleValue();
        double hedge = ((Number) params.get("fx_hedge_ratio")).doubleValue();
        int backup = ((Number) params.get("backup_options")).intValue();
        double insurance = ((Number) params.get("insurance_ratio")).doubleValue();

        // 步骤 1：风险识别与概率
        double protection = hedge + backup * 0.2 + insurance;
        ctx.step(String.format("货值 %.0f 万元；汇率波动 ±%.0f%%（年化）、政治风险 %.0f%%、自然风险 %.0f%%",
                        CARGO_VALUE, fxVol * 100, polProb * 100, natProb * 100),
                Map.of("fx_volatility", fxVol, "political_risk_prob", polProb,
                        "natural_risk_prob", natProb));

        // 步骤 2：对冲与防护设计
        ctx.step(String.format("防护组合：汇率对冲 %.0f%% + 备选路线 %d 个 + 保险 %.0f%% → 综合防护 %.0f%%",
                        hedge * 100, backup, insurance * 100, protection * 100),
                Map.of("protection_level", round2(protection)));

        // 步骤 3：期望损失 + Monte Carlo 95% VaR（内存抽样，无步骤事件）
        double expLoss = expectedLoss(hedge, backup, insurance, fxVol, polProb, natProb);
        double fxStd = CARGO_VALUE * fxVol * (1 - hedge);
        double polLoss = CARGO_VALUE * 0.4 * Math.max(0, 1 - backup * 0.15 - insurance * 0.15);
        double natLoss = CARGO_VALUE * 0.3 * Math.max(0, 1 - insurance * 0.8 - backup * 0.1);
        int n = 5000;
        double[] losses = new double[n];
        for (int i = 0; i < n; i++) {
            double fx = Math.abs(ctx.random().nextGaussian()) * fxStd;
            double pol = ctx.random().nextDouble() < polProb ? polLoss : 0;
            double nat = ctx.random().nextDouble() < natProb ? natLoss : 0;
            losses[i] = fx + pol + nat;
        }
        Arrays.sort(losses);
        double var95 = losses[(int) (n * 0.95)];
        ctx.step(String.format("期望损失 %.1f 万元；Monte Carlo 5000 次抽样 → 95%% VaR = %.1f 万元",
                        expLoss, var95),
                Map.of("expected_loss", round2(expLoss), "var_95", round2(var95), "samples", n));

        // 步骤 4：对冲有效性 & 情景对比
        double unhedgedFx = CARGO_VALUE * fxVol;
        double hedgeCost = unhedgedFx * hedge * 0.10;
        List<Map<String, Object>> hedgeEffect = List.of(
                Map.of("name", "未对冲风险", "value", round2(unhedgedFx)),
                Map.of("name", "对冲后风险", "value", round2(unhedgedFx * (1 - hedge))),
                Map.of("name", "对冲成本", "value", round2(hedgeCost)),
                Map.of("name", "净风险降低", "value", round2(unhedgedFx - unhedgedFx * (1 - hedge) - hedgeCost)));
        List<Map<String, Object>> scenarios = List.of(
                Map.of("name", "保守策略(全对冲+全保险)", "value",
                        round2(expectedLoss(1.0, 3, 1.0, fxVol, polProb, natProb))),
                Map.of("name", "平衡策略(当前)", "value", round2(expLoss)),
                Map.of("name", "激进策略(低对冲)", "value",
                        round2(expectedLoss(0.2, 0, 0.2, fxVol, polProb, natProb))),
                Map.of("name", "无防护", "value",
                        round2(expectedLoss(0, 0, 0, fxVol, polProb, natProb))));
        ctx.step("对冲成本 vs 风险降低 对比完成；四种策略情景损失见数据",
                Map.of("hedge_effectiveness", hedgeEffect, "scenarios", scenarios));

        // 步骤 5：恢复时间分布（dist，天）
        double meanDays = Math.max(3, 21 - backup * 4 - insurance * 5);
        int[] buckets = {0, 0, 0, 0};
        int samples = 600;
        for (int i = 0; i < samples; i++) {
            double days = Math.max(1, meanDays + ctx.random().nextGaussian() * 6);
            if (days < 7) {
                buckets[0]++;
            } else if (days < 14) {
                buckets[1]++;
            } else if (days < 30) {
                buckets[2]++;
            } else {
                buckets[3]++;
            }
        }
        List<Map<String, Object>> recoveryDist = List.of(
                Map.of("name", "<7天", "value", buckets[0]),
                Map.of("name", "7-14天", "value", buckets[1]),
                Map.of("name", "14-30天", "value", buckets[2]),
                Map.of("name", ">30天", "value", buckets[3]));
        ctx.step(String.format("平均恢复时间 %.0f 天（备选路线 -%d 天/个，保险 -%d 天/覆盖度）",
                        meanDays, backup * 4, (int) (insurance * 5)),
                Map.of("recovery_mean_days", round2(meanDays), "recovery_dist", recoveryDist));

        // 输出指标（FR-007）
        ctx.output("expected_loss", "期望损失", "scalar", round2(expLoss), "万元");
        ctx.output("var_95", "风险价值VaR", "scalar", round2(var95), "万元");
        ctx.output("hedge_effectiveness", "对冲成本vs风险降低", "compare", hedgeEffect, "万元");
        ctx.output("scenario_analysis", "不同策略情景分析", "compare", scenarios, null);
        ctx.output("recovery_time", "恢复时间分布", "dist", recoveryDist, "天");
    }
}
