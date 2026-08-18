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
 * 全球供应链金融（跨境融资）仿真执行器（T061，CH10-005）。
 * 模型：中国出口商 → 美国进口商（30-90 天账期）→ 四种收款方案：裸奔（无融资无对冲）、信用证 L/C
 * （开证费 + 消除信用风险）、出口保理（贴现卖断提前收款）、远期结汇（对冲比例锁定汇率）；模拟
 * 人民币升值 3% 情景比较各方案实际收款（CNY）与汇率风险暴露，给出最优融资+对冲组合。
 */
@Component
public class CbFinanceExecutor implements ScenarioExecutor {

    private static final double APPRECIATION = 0.03;   // 人民币升值情景（3%）
    private static final double COST_OF_FUNDS = 0.03;  // 资金成本（年化，用于保理提前收款对比）

    @Override
    public String engineKey() {
        return "cb-finance";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "contract_amount", 10, 1000, errors);
        intParam(params, "payment_terms", 30, 180, errors);
        doubleParam(params, "exchange_rate", 6.5, 7.5, errors);
        doubleParam(params, "fx_volatility", 0.03, 0.10, errors);
        doubleParam(params, "lc_fee_rate", 1, 3, errors);
        Double factoring = doubleParam(params, "factoring_discount_rate", 0.03, 0.08, errors);
        doubleParam(params, "forward_rate", 6.3, 7.4, errors);
        enumParam(params, "importer_rating", Set.of("AAA", "AA", "A", "BBB"), errors);
        doubleParam(params, "hedge_ratio", 0, 1, errors);
        if (errors.isEmpty() && factoring != null && factoring >= 0.08) {
            errors.add("cost_within_margin 约束不满足：融资成本需不超过利润率");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double amountUsd = ((Number) params.get("contract_amount")).doubleValue() * 10000; // 美元
        int terms = ((Number) params.get("payment_terms")).intValue();                     // 天
        double spot = ((Number) params.get("exchange_rate")).doubleValue();                // CNY/USD
        double lcFee = ((Number) params.get("lc_fee_rate")).doubleValue();                 // %
        double factoringRate = ((Number) params.get("factoring_discount_rate")).doubleValue(); // 年化
        double forward = ((Number) params.get("forward_rate")).doubleValue();
        String rating = String.valueOf(params.get("importer_rating"));
        double hedgeRatio = ((Number) params.get("hedge_ratio")).doubleValue();

        // 步骤 1：贸易背景与汇率情景（人民币升值 3%）
        double spotMaturity = spot * (1 - APPRECIATION);
        double defaultProb = switch (rating) {         // 进口商违约概率（无 L/C 时）
            case "AAA" -> 0.003;
            case "AA" -> 0.008;
            case "A" -> 0.015;
            default -> 0.03;
        };
        ctx.step(String.format("出口合同 %,.0f 万美元（账期 %d 天）；当前汇率 %.2f，人民币升值 3%% 情景"
                        + " → 到期即期 %.2f；进口商评级 %s（违约概率 %.2f%%）",
                amountUsd / 10000, terms, spot, spotMaturity, rating, defaultProb * 100),
                Map.of("contract_usd", round2(amountUsd), "spot_at_maturity", round2(spotMaturity)));

        // 步骤 2：四种方案成本对比
        double lcCost = amountUsd * lcFee / 100 * spot;                       // L/C 开证费（元）
        double factoringCost = amountUsd * factoringRate * terms / 360 * spot; // 保理贴现（元）
        double forwardDiscount = amountUsd * hedgeRatio * (spot - forward);    // 远期贴水（元）
        double noHedgeFxLoss = amountUsd * (spot - spotMaturity);              // 裸奔汇率损失（元）
        List<Map<String, Object>> costCompare = List.of(
                Map.of("name", "裸奔-汇率损失", "value", round2(noHedgeFxLoss)),
                Map.of("name", "L/C开证费", "value", round2(lcCost)),
                Map.of("name", "保理贴现成本", "value", round2(factoringCost)),
                Map.of("name", "远期贴水成本", "value", round2(forwardDiscount)));
        ctx.step(String.format("成本对比：裸奔汇率损失 %,.0f 元 / L/C 开证费 %,.0f 元 / 保理贴现 %,.0f 元"
                        + " / 远期贴水 %,.0f 元",
                noHedgeFxLoss, lcCost, factoringCost, forwardDiscount),
                Map.of("cost_compare", costCompare));

        // 步骤 3：汇率风险暴露与各方案实际收款（CNY）
        double fxExposure = amountUsd * (1 - hedgeRatio) * (spot - spotMaturity);
        double receiptNoHedge = amountUsd * spotMaturity * (1 - defaultProb);
        double receiptLc = amountUsd * spotMaturity - lcCost;
        double receiptFactoring = amountUsd * spot * (1 - factoringRate * terms / 360)
                - amountUsd * spot * COST_OF_FUNDS * terms / 360 * (1 - hedgeRatio);
        double receiptHedged = amountUsd * (hedgeRatio * forward + (1 - hedgeRatio) * spotMaturity)
                - lcCost - forwardDiscount;
        List<Map<String, Object>> receiptCompare = List.of(
                Map.of("name", "裸奔收款", "value", round2(receiptNoHedge)),
                Map.of("name", "L/C+裸奔", "value", round2(receiptLc)),
                Map.of("name", "出口保理", "value", round2(receiptFactoring)),
                Map.of("name", "L/C+远期对冲", "value", round2(receiptHedged)));
        ctx.step(String.format("汇率风险暴露 %,.0f 元（未对冲比例 %.0f%%）；实际收款：裸奔 %,.0f / "
                        + "L/C %,.0f / 保理 %,.0f / L/C+远期 %,.0f 元",
                fxExposure, (1 - hedgeRatio) * 100, receiptNoHedge, receiptLc,
                receiptFactoring, receiptHedged),
                Map.of("fx_exposure", round2(fxExposure), "actual_receipt_compare", receiptCompare));

        // 步骤 4：最优融资+对冲组合
        String best;
        double bestReceipt = Math.max(Math.max(receiptNoHedge, receiptLc),
                Math.max(receiptFactoring, receiptHedged));
        if (bestReceipt == receiptHedged) {
            best = "L/C 信用证 + 远期结汇（对冲比例 " + (int) (hedgeRatio * 100) + "%）";
        } else if (bestReceipt == receiptFactoring) {
            best = "出口保理（应收账款卖断提前收款）";
        } else if (bestReceipt == receiptLc) {
            best = "L/C 信用证（消除信用风险）";
        } else {
            best = "裸奔（风险自留，仅适合短期小额）";
        }
        ctx.step(String.format("最优组合：【%s】（收款 %,.0f 元）", best, bestReceipt),
                Map.of("best_combo", best));

        // 步骤 5：风险提示汇总
        ctx.step("提示：L/C 消除进口商信用风险但增加开证成本；保理卖断应收账款可提前回款但贴现率随账期"
                        + "线性上升；远期结汇锁定汇率但放弃升值收益——人民币升值情景下对冲比例越高损失越小",
                Map.of("appreciation_scenario", "CNY 升值 3%"));

        // 输出指标（FR-007）
        ctx.output("cost_compare", "各融资方案成本对比", "compare", costCompare, "元");
        ctx.output("fx_exposure", "汇率风险暴露", "scalar", round2(fxExposure), "元");
        ctx.output("actual_receipt_compare", "实际收款金额(CNY)", "compare", receiptCompare, "元");
        ctx.output("best_combo", "最优融资+对冲组合", "scalar", best, null);
    }
}
