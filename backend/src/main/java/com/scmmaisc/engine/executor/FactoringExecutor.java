package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 应收账款融资（保理）仿真执行器（T030，CH9-001）。
 * 模型：核心企业确权后按质押率放款；融资利率 = 基准利率 × 评级系数；银行期望损失按
 * 违约概率 × 违约损失率（有追索权由供应商回购 → 银行损失小；无追索权由银行承担）。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class FactoringExecutor implements ScenarioExecutor {

    private static final Set<String> RATINGS = Set.of("AAA", "AA", "A", "BBB");
    private static final Set<String> RECOURSE = Set.of("recourse", "non_recourse");

    @Override
    public String engineKey() {
        return "factoring";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double amount = doubleParam(params, "receivable_amount", 100, 10_000, errors);
        Integer period = intParam(params, "receivables_period", 30, 180, errors);
        Double pledgeRate = doubleParam(params, "pledge_rate", 0.6, 0.8, errors);
        Double rate = doubleParam(params, "financing_rate", 0.04, 0.12, errors);
        String rating = enumParam(params, "credit_rating", RATINGS, errors);
        Double defaultProb = doubleParam(params, "default_probability", 0.001, 0.05, errors);
        String recourse = enumParam(params, "recourse_type", RECOURSE, errors);
        Double history = doubleParam(params, "historical_performance", 0.85, 1.0, errors);
        if (errors.isEmpty() && amount != null && period != null && pledgeRate != null
                && rate != null && rating != null && defaultProb != null && recourse != null && history != null) {
            // 约束 receivable_valid：核心企业确权 + 历史履约充分
            if (history < 0.9) {
                errors.add(String.format("receivable_valid 约束不满足：应收账款需真实有效并经核心企业确权（历史履约率 %.0f%% < 90%%）",
                        history * 100));
            }
            // 约束 pledge_ratio_ok：质押率不得超过 80%
            if (pledgeRate > 0.8) {
                errors.add("pledge_ratio_ok 约束不满足：质押率不得超过 80%");
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
        double amount = ((Number) params.get("receivable_amount")).doubleValue();
        int period = ((Number) params.get("receivables_period")).intValue();
        double pledgeRate = ((Number) params.get("pledge_rate")).doubleValue();
        double rate = ((Number) params.get("financing_rate")).doubleValue();
        String rating = String.valueOf(params.get("credit_rating"));
        double defaultProb = ((Number) params.get("default_probability")).doubleValue();
        String recourse = String.valueOf(params.get("recourse_type"));
        double history = ((Number) params.get("historical_performance")).doubleValue();

        // 评级系数：AAA 0.9 / AA 1.0 / A 1.15 / BBB 1.3
        double ratingFactor = switch (rating) {
            case "AAA" -> 0.9;
            case "A" -> 1.15;
            case "BBB" -> 1.3;
            default -> 1.0; // AA
        };
        double effectiveRate = rate * ratingFactor;
        double exposure = amount * pledgeRate;                      // 融资金额（万元）
        double financeCost = exposure * 10_000 * effectiveRate * period / 365.0;  // 元
        int approvalDays = history >= 0.95 ? 2 : 5;
        int cashflowDays = period - approvalDays;                   // 现金流改善天数
        double lgd = 0.6;                                           // 违约损失率 60%

        // 步骤 1：应收账款确认
        ctx.step(String.format("应收账款确认：%.0f 万元，账期 %d 天，核心企业信用评级 %s，历史履约率 %.0f%%（%s）",
                amount, period, rating, history * 100, history >= 0.9 ? "已确权" : "确权存疑"),
                Map.of("receivable_amount", round2(amount), "confirmed", history >= 0.9));

        // 步骤 2：审批与放款
        double financeAmount = exposure;
        ctx.step(String.format("审批放款：质押率 %.0f%% → 融资金额 %.0f 万元；基准利率 %.1f%% × 评级系数 %.2f → 实际利率 %.2f%%",
                pledgeRate * 100, financeAmount, rate * 100, ratingFactor, effectiveRate * 100),
                Map.of("finance_amount", round2(financeAmount), "effective_rate", round2(effectiveRate * 100)));

        // 步骤 3：现金流改善
        ctx.step(String.format("现金流改善：放款 %d 天到账，账期 %d 天 → 供应商资金回笼提前 %d 天，融资成本 %.0f 元",
                approvalDays, period, cashflowDays, financeCost),
                Map.of("cashflow_improvement_days", cashflowDays, "finance_cost", round2(financeCost)));

        // 步骤 4：违约情景（追索权差异）
        double bankLoss, supplierLoss;
        if ("non_recourse".equals(recourse)) {
            bankLoss = exposure * 10_000 * defaultProb * lgd;       // 银行承担全部违约损失
            supplierLoss = financeCost;                             // 供应商仅付出融资成本
        } else {
            bankLoss = exposure * 10_000 * defaultProb * 0.1;       // 有追索权：供应商回购，银行仅摩擦损失
            supplierLoss = exposure * 10_000 + financeCost;         // 供应商回购应收 + 已付融资成本
        }
        ctx.step(String.format("违约情景：核心企业违约概率 %.2f%%；%s —— 银行期望损失 %.0f 元，供应商损失 %.0f 元",
                defaultProb * 100, "non_recourse".equals(recourse) ? "无追索权（银行承担）" : "有追索权（供应商回购）",
                bankLoss, supplierLoss),
                Map.of("bank_expected_loss", round2(bankLoss), "supplier_default_loss", round2(supplierLoss)));

        // 步骤 5：结论
        String advice = cashflowDays >= 30 ? "融资显著改善营运资金，建议采用保理方案" : "账期较短，融资收益有限，可权衡手续费";
        ctx.step(String.format("结论：%s（现金流提前 %d 天，年化成本 %.2f%%）", advice, cashflowDays, effectiveRate * 100),
                Map.of("advice", advice));

        // 输出指标（FR-007）
        ctx.output("finance_amount", "融资金额", "scalar", round2(financeAmount), "万元");
        ctx.output("finance_cost", "融资成本", "scalar", round2(financeCost), "元");
        ctx.output("bank_expected_loss", "银行期望损失", "scalar", round2(bankLoss), "元");
        ctx.output("cashflow_improvement_days", "供应商现金流改善天数", "scalar", cashflowDays, "天");
        ctx.output("default_loss_compare", "违约情景损失", "compare",
                List.of(Map.of("name", "有追索权（供应商承担）", "value", round2(supplierLoss)),
                        Map.of("name", "无追索权（银行承担）", "value", round2(bankLoss))),
                "元");
    }
}
