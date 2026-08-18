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
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 库存质押融资动态仿真执行器（T060，CH9-002）。
 * 模型：存货/仓单质押 → 银行按质押率放款 → 日模拟：销售赎货（质押物流出）+ 定期补货（追加质押）
 * + 价格波动（几何布朗运动，漂移 μ + 波动 σ×N(0,1)）→ 质押率 = 贷款/质押物价值，触及警戒线
 * 追加保证金、触及平仓线强制处置 → Monte Carlo 多路径评估平仓概率 → 银行敞口与企业可用额度曲线。
 */
@Component
public class InventoryPledgeExecutor implements ScenarioExecutor {

    private static final int MC_PATHS = 200;   // 平仓概率蒙特卡洛路径数
    private static final double INITIAL_QTY = 10000.0; // 初始质押物数量（件）

    @Override
    public String engineKey() {
        return "inventory-pledge";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "initial_inventory_value", 500, 50000, errors);
        Double pledge = doubleParam(params, "initial_pledge_rate", 0.5, 0.7, errors);
        Double warning = doubleParam(params, "warning_line", 0.5, 0.8, errors);
        Double liquidation = doubleParam(params, "liquidation_line", 0.7, 0.95, errors);
        doubleParam(params, "price_volatility", 0.005, 0.03, errors);
        doubleParam(params, "price_drift", -0.001, 0.001, errors);
        doubleParam(params, "daily_redeem_qty", 10, 500, errors);
        intParam(params, "restock_cycle", 1, 30, errors);
        enumParam(params, "collateral_type", Set.of("bulk_commodity", "standard_industrial", "consumer_goods"), errors);
        doubleParam(params, "disposal_discount", 0.7, 0.9, errors);
        intParam(params, "sim_days", 30, 360, errors);
        if (errors.isEmpty() && warning != null && liquidation != null && pledge != null) {
            if (warning >= liquidation) {
                errors.add("warning_below_liquidation 约束不满足：警戒线必须低于平仓线（警戒触发追加，平仓触发处置）");
            }
            if (pledge >= liquidation) {
                errors.add("no_over_pledge 约束不满足：初始质押率不得超出平仓线");
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
        double initValue = ((Number) params.get("initial_inventory_value")).doubleValue(); // 万元
        double pledgeRate0 = ((Number) params.get("initial_pledge_rate")).doubleValue();
        double warningLine = ((Number) params.get("warning_line")).doubleValue();
        double liquidationLine = ((Number) params.get("liquidation_line")).doubleValue();
        double vol = ((Number) params.get("price_volatility")).doubleValue();
        double drift = ((Number) params.get("price_drift")).doubleValue();
        double redeem = ((Number) params.get("daily_redeem_qty")).doubleValue();
        int restock = ((Number) params.get("restock_cycle")).intValue();
        String collateral = String.valueOf(params.get("collateral_type"));
        double disposal = ((Number) params.get("disposal_discount")).doubleValue();
        int days = ((Number) params.get("sim_days")).intValue();

        // 步骤 1：参数设定与初始质押
        double loan = initValue * pledgeRate0;                       // 贷款（万元）
        double price0 = initValue * 10000 / INITIAL_QTY;             // 初始单价（元/件）
        double liquidityFactor = switch (collateral) {
            case "bulk_commodity" -> 1.3;        // 大宗商品价格波动更剧烈
            case "consumer_goods" -> 0.8;        // 消费品相对平稳
            default -> 1.0;
        };
        ctx.step(String.format("质押设定：质押物价值 %,.0f 万元（%s），质押率 %.0f%% → 放款 %,.0f 万元；"
                        + "警戒线 %.0f%% / 平仓线 %.0f%%",
                initValue, collateral, pledgeRate0 * 100, loan, warningLine * 100, liquidationLine * 100),
                Map.of("loan_amount", round2(loan), "initial_pledge_rate", round2(pledgeRate0 * 100)));

        // 步骤 2：日模拟主路径（赎货 + 补货 + 价格波动 → 质押率曲线与警戒触发）
        double qty = INITIAL_QTY;
        double price = price0;
        int marginCalls = 0;
        boolean liquidated = false;
        double bankLoss = 0;
        double recovered = 0;
        List<Double> pledgeCurve = new ArrayList<>();
        List<Double> exposureCurve = new ArrayList<>();
        List<Double> creditCurve = new ArrayList<>();
        for (int t = 1; t <= days; t++) {
            price *= (1 + drift + vol * liquidityFactor * ctx.random().nextGaussian());
            qty = Math.max(0, qty - redeem);                          // 销售赎货
            if (t % restock == 0) {
                qty += redeem * restock;                              // 定期补货（追加质押）
            }
            double value = qty * price;                               // 元
            double pledge = value > 0 ? loan * 10000 / value : 1.0;
            pledgeCurve.add(round2(pledge * 100));
            double recoverable = value * disposal;
            exposureCurve.add(round2(Math.max(0, loan * 10000 - recoverable)));
            creditCurve.add(round2(Math.max(0, value * pledgeRate0 - loan * 10000)));
            if (!liquidated && pledge >= warningLine) {
                marginCalls++;                                        // 追加保证金（压降质押率）
                loan -= value * 0.02 / 10000;
            }
            if (!liquidated && pledge >= liquidationLine) {
                liquidated = true;
                recovered = value * disposal;                         // 平仓处置（折价）
                bankLoss = Math.max(0, loan * 10000 - recovered);
            }
        }
        double finalPledge = pledgeCurve.get(pledgeCurve.size() - 1);
        ctx.step(String.format("日模拟 %d 天：追加保证金 %d 次，期末质押率 %.1f%%%s",
                        days, marginCalls, finalPledge, liquidated ? "（已触发平仓）" : "（未触发平仓）"),
                Map.of("margin_call_count", marginCalls, "final_pledge_rate", round2(finalPledge)));

        // 步骤 3：平仓情景（银行处置回收与损失）
        if (liquidated) {
            ctx.step(String.format("平仓处置：回收 %,.0f 元（折价率 %.0f%%），银行损失 %,.0f 元",
                            recovered, disposal * 100, bankLoss),
                    Map.of("recovered_amount", round2(recovered), "bank_loss", round2(bankLoss)));
        } else {
            ctx.step(String.format("未触发平仓：期末质押物价值 %,.0f 元 ≥ 贷款 %,.0f 元，风险可控",
                            qty * price, loan * 10000),
                    Map.of("collateral_value", round2(qty * price)));
        }

        // 步骤 4：Monte Carlo 平仓概率（价格路径抽样）
        int liquidationCount = 0;
        for (int path = 0; path < MC_PATHS; path++) {
            double p = price0;
            double q = INITIAL_QTY;
            double l = loan;
            for (int t = 1; t <= days; t++) {
                p *= (1 + drift + vol * liquidityFactor * ctx.random().nextGaussian());
                q = Math.max(0, q - redeem);
                if (t % restock == 0) {
                    q += redeem * restock;
                }
                double v = q * p;
                if (v > 0 && l * 10000 / v >= warningLine) {
                    l -= v * 0.02 / 10000;
                }
                if (v > 0 && l * 10000 / v >= liquidationLine) {
                    liquidationCount++;
                    break;
                }
            }
        }
        double liquidationProb = liquidationCount * 100.0 / MC_PATHS;
        ctx.step(String.format("Monte Carlo %d 条价格路径：%d 条触发平仓 → 平仓概率 %.1f%%",
                        MC_PATHS, liquidationCount, liquidationProb),
                Map.of("liquidation_probability", round2(liquidationProb)));

        // 步骤 5：曲线汇总输出
        Map<String, Object> pledgeSeries = new LinkedHashMap<>();
        pledgeSeries.put("x", daysAxis(days));
        pledgeSeries.put("series", List.of(Map.of("name", "质押率", "data", pledgeCurve)));
        Map<String, Object> exposureSeries = new LinkedHashMap<>();
        exposureSeries.put("x", daysAxis(days));
        exposureSeries.put("series", List.of(Map.of("name", "银行敞口", "data", exposureCurve)));
        Map<String, Object> creditSeries = new LinkedHashMap<>();
        creditSeries.put("x", daysAxis(days));
        creditSeries.put("series", List.of(Map.of("name", "可用额度", "data", creditCurve)));
        ctx.step("质押率/银行敞口/可用额度曲线已生成（含警戒线与平仓线触发逻辑）",
                Map.of("pledge_rate_series", pledgeSeries, "bank_exposure_series", exposureSeries,
                        "available_credit_series", creditSeries));

        // 输出指标（FR-007）
        ctx.output("pledge_rate_series", "质押率动态变化曲线", "series", pledgeSeries, "%");
        ctx.output("margin_call_count", "追加保证金次数", "scalar", marginCalls, "次");
        ctx.output("liquidation_probability", "平仓概率", "scalar", round2(liquidationProb), "%");
        ctx.output("bank_exposure_series", "银行风险敞口", "series", exposureSeries, "元");
        ctx.output("available_credit_series", "企业资金可用额度变化", "series", creditSeries, "元");
    }

    /** 1..days 天刻度。 */
    private static List<Double> daysAxis(int days) {
        List<Double> x = new ArrayList<>();
        for (int i = 1; i <= days; i++) {
            x.add((double) i);
        }
        return x;
    }
}
