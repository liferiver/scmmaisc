package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 预付款/保兑仓融资仿真执行器（T060，CH9-003）。
 * 模型：经销商预付款采购 → 银行定向支付给核心企业（未来货权质押）→ 核心企业担保/回购/差额补足
 * 承诺 → 货物入库转存货质押 → 销售回款分批赎货还贷；违约情景下评估追偿回收率（回购承诺 + 折价
 * 处置质押物，受信用评级与监管方可靠性调整），并测算经销商 ROE 杠杆效应与各方损益。
 */
@Component
public class AdvancePaymentExecutor implements ScenarioExecutor {

    private static final double SALES_MARGIN = 0.15;    // 经销商销售毛利率
    private static final double CORE_MARGIN = 0.05;     // 核心企业销售毛利率

    @Override
    public String engineKey() {
        return "advance-payment";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "purchase_amount", 200, 20000, errors);
        doubleParam(params, "self_fund_ratio", 0.2, 0.5, errors);
        Double bank = doubleParam(params, "bank_finance_ratio", 0.5, 0.8, errors);
        doubleParam(params, "repurchase_commitment", 0.5, 1.0, errors);
        enumParam(params, "credit_rating", Set.of("AAA", "AA", "A"), errors);
        doubleParam(params, "price_volatility", 0.1, 0.3, errors);
        doubleParam(params, "payment_cycle", 30, 180, errors);
        enumParam(params, "warehouse_supervisor", Set.of("bank_designated", "third_party", "core_enterprise"), errors);
        if (errors.isEmpty() && bank != null && bank > 0.8) {
            errors.add("directed_payment 约束不满足：银行资金需定向支付给核心企业（不流经经销商），货物需全程监管");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double purchase = ((Number) params.get("purchase_amount")).doubleValue(); // 万元
        double selfRatio = ((Number) params.get("self_fund_ratio")).doubleValue();
        double bankRatio = ((Number) params.get("bank_finance_ratio")).doubleValue();
        double commitment = ((Number) params.get("repurchase_commitment")).doubleValue();
        String rating = String.valueOf(params.get("credit_rating"));
        double vol = ((Number) params.get("price_volatility")).doubleValue();
        double cycle = ((Number) params.get("payment_cycle")).doubleValue();
        String supervisor = String.valueOf(params.get("warehouse_supervisor"));

        double loan = purchase * bankRatio;              // 银行融资本金（万元）
        double selfFund = purchase * selfRatio;          // 经销商自有资金（万元）
        double gap = Math.max(0, purchase - loan - selfFund); // 资金缺口由经销商补足

        // 步骤 1：融资结构设定（T0 资金流）
        double rate = switch (rating) {                  // 年化利率（评级驱动）
            case "AAA" -> 0.045;
            case "AA" -> 0.055;
            default -> 0.065;
        };
        double defaultProb = switch (rating) {           // 经销商违约概率
            case "AAA" -> 0.005;
            case "AA" -> 0.01;
            default -> 0.02;
        };
        ctx.step(String.format("融资结构：采购总额 %,.0f 万元 = 自有 %.0f%% + 银行 %.0f%%；"
                        + "银行放款 %,.0f 万元定向支付给核心企业（评级 %s，利率 %.1f%%）",
                purchase, selfRatio * 100, bankRatio * 100, loan, rating, rate * 100),
                Map.of("loan_amount", round2(loan), "self_fund", round2(selfFund), "gap", round2(gap)));

        // 步骤 2：定向支付、货物入库与监管（未来货权质押 → 存货质押）
        double superviseFactor = switch (supervisor) {
            case "bank_designated" -> 1.0;
            case "third_party" -> 0.97;
            default -> 0.93;                            // 核心企业自监管可靠性偏低
        };
        ctx.step(String.format("银行向核心企业定向支付 %,.0f 万元 → 核心企业发货至指定仓库（监管方：%s，"
                        + "可靠性 %.0f%%）→ 货物入库转为存货质押；核心企业提供回购承诺 %.0f%%",
                loan, supervisor, superviseFactor * 100, commitment * 100),
                Map.of("directed_payment", round2(loan), "repurchase_commitment", round2(commitment)));

        // 步骤 3：销售回款分批赎货还贷
        double collateralDrop = purchase * vol;          // 价格波动导致的质押物价值损失（万元）
        double collateralValue = purchase - collateralDrop;
        ctx.step(String.format("销售回款周期 %.0f 天分批赎货还贷；若价格下跌 %.0f%%，质押物价值 "
                        + "%,.0f 万元（原 %,.0f 万元）",
                cycle, vol * 100, collateralValue, purchase),
                Map.of("payment_cycle", round2(cycle), "collateral_value_after_drop", round2(collateralValue)));

        // 步骤 4：违约情景与银行追偿回收率
        double coreRecovery = loan * commitment;         // 核心企业回购承诺回收（万元）
        double remaining = loan - coreRecovery;          // 剩余敞口靠质押物处置
        double disposalRecovery = remaining * (1 - vol) * 0.7 * superviseFactor;
        double recovery = Math.min(loan, coreRecovery + disposalRecovery);
        double recoveryRate = recovery / loan * 100;
        double expectedLoss = (1 - recoveryRate / 100) * loan * defaultProb;
        ctx.step(String.format("违约情景：核心企业回购 %,.0f 万 + 质押物折价处置 %,.0f 万 → 追偿回收率 "
                        + "%.1f%%；银行期望损失 %,.0f 万元",
                coreRecovery, disposalRecovery, recoveryRate, expectedLoss),
                Map.of("recovery_rate", round2(recoveryRate), "expected_loss", round2(expectedLoss)));

        // 步骤 5：ROE 杠杆效应、各方损益与现金流时间线
        double interest = loan * rate * cycle / 365;     // 融资利息（万元）
        double dealerPnl = purchase * SALES_MARGIN - interest;
        double dealerEquity = selfFund + gap;
        double roeFinanced = dealerEquity > 0 ? dealerPnl / dealerEquity * 100 : 0;
        double roeCash = SALES_MARGIN * 100;
        double bankPnl = interest - expectedLoss;
        double corePnl = purchase * CORE_MARGIN - defaultProb * loan * commitment;
        List<Map<String, Object>> partyPnl = List.of(
                Map.of("name", "经销商损益", "value", round2(dealerPnl * 10000)),
                Map.of("name", "银行损益", "value", round2(bankPnl * 10000)),
                Map.of("name", "核心企业损益", "value", round2(corePnl * 10000)));
        Map<String, Object> timeline = cashflowTimeline(purchase, selfFund, loan, gap, interest, cycle, defaultProb, commitment);
        ctx.step(String.format("ROE 杠杆：融资后 %.1f%% vs 全款采购 %.1f%%（放大 %.1f 倍）；"
                        + "各方损益：经销商 %,.0f / 银行 %,.0f / 核心企业 %,.0f 万元",
                roeFinanced, roeCash, roeFinanced / Math.max(1e-9, roeCash),
                dealerPnl, bankPnl, corePnl),
                Map.of("party_pnl", partyPnl, "dealer_roe_leverage", round2(roeFinanced)));

        // 输出指标（FR-007）
        ctx.output("cashflow_timeline", "各方资金流动时间线", "series", timeline, "元");
        ctx.output("bank_exposure_series", "银行风险敞口变化", "series",
                bankExposureSeries(loan, cycle), "元");
        ctx.output("dealer_roe_leverage", "经销商ROE杠杆效应", "scalar", round2(roeFinanced), "%");
        ctx.output("recovery_rate", "违约后银行追偿回收率", "scalar", round2(recoveryRate), "%");
        ctx.output("party_pnl", "各方损益", "compare", partyPnl, "元");
    }

    /** 各方资金流动时间线（T0 放款/定向支付/发货 → 回款 → 结清），元。 */
    private static Map<String, Object> cashflowTimeline(double purchase, double selfFund, double loan,
                                                        double gap, double interest, double cycle,
                                                        double defaultProb, double commitment) {
        double coreRecoveryExpected = defaultProb * loan * commitment * 10000;
        List<Double> x = List.of(0.0, cycle / 3, cycle * 2 / 3, cycle);
        List<Double> dealer = List.of(
                round2(-(selfFund + gap) * 10000),
                round2(purchase * 10000 * 0.1),
                round2(purchase * 10000 * 0.1),
                round2(purchase * 10000 * 0.15 - interest * 10000));
        List<Double> bank = List.of(
                round2(-loan * 10000),
                round2(loan * 10000 / 3),
                round2(loan * 10000 * 2 / 3),
                round2(loan * 10000 + interest * 10000));
        List<Double> core = List.of(
                round2(purchase * 10000),
                round2(purchase * 10000 * 0.1),
                round2(purchase * 10000 * 0.1),
                round2(purchase * 10000 * 0.05 - coreRecoveryExpected));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("series", List.of(
                Map.of("name", "经销商", "data", dealer),
                Map.of("name", "银行", "data", bank),
                Map.of("name", "核心企业", "data", core)));
        return m;
    }

    /** 银行敞口随赎货进度线性下降（元）。 */
    private static Map<String, Object> bankExposureSeries(double loan, double cycle) {
        List<Double> x = new ArrayList<>();
        List<Double> data = new ArrayList<>();
        for (int t = 0; t <= 4; t++) {
            x.add(t * cycle / 4);
            data.add(round2(loan * 10000 * (1 - t / 4.0)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("series", List.of(Map.of("name", "银行敞口", "data", data)));
        return m;
    }
}
