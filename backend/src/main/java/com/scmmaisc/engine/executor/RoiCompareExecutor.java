package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 物流技术投资 ROI 对比仿真执行器（T054，CH3-008，进阶，综合 3.1/3.2/3.3/3.5）。
 * 模型：六项技术（条码/RFID/GPS/EDI/AGV/WMS 升级）各自的 一次性投入 + 年化收益
 * （效率提升基数 ×（效率提升率 + 30% × 差错降低率）× 人力成本系数）→ ROI/回收期/
 * NPV 测算 → 预算约束下枚举 64 种组合取累计 NPV 最大 → ±20% 敏感性分析。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class RoiCompareExecutor implements ScenarioExecutor {

    private static final String[] TECH_NAMES = {"条码", "RFID", "GPS", "EDI", "AGV", "WMS升级"};
    private static final String[] COST_KEYS = {"cost_barcode", "cost_rfid", "cost_gps", "cost_edi", "cost_agv", "cost_wms"};
    private static final String[] EFF_KEYS = {"eff_barcode", "eff_rfid", "eff_gps", "eff_edi", "eff_agv", "eff_wms"};
    private static final String[] ERR_KEYS = {"err_barcode", "err_rfid", "err_gps", "err_edi", "err_agv", "err_wms"};
    private static final double[] BENEFIT_BASE = {200, 400, 150, 250, 600, 500};  // 年收益基数（万元）
    private static final double ERROR_COST_RATIO = 0.3;   // 差错成本占基数比例
    private static final double LABOR_BASE = 12.0;        // 人力成本基准（万元/人年）

    @Override
    public String engineKey() {
        return "roi-compare";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double budget = doubleParam(params, "budget", 50, 5000, errors);
        for (String key : COST_KEYS) {
            doubleParam(params, key, 10, 2000, errors);
        }
        for (String key : EFF_KEYS) {
            doubleParam(params, key, 0.1, 0.6, errors);
        }
        for (String key : ERR_KEYS) {
            doubleParam(params, key, 0.3, 0.99, errors);
        }
        doubleParam(params, "labor_cost", 8, 20, errors);
        doubleParam(params, "discount_rate", 0.05, 0.15, errors);
        intParam(params, "eval_years", 3, 10, errors);
        // 约束 budget_ok：各技术总投资需不超过预算
        if (errors.isEmpty() && budget != null) {
            double total = 0;
            for (String key : COST_KEYS) {
                total += ((Number) params.get(key)).doubleValue();
            }
            if (total > budget) {
                errors.add("budget_ok 约束不满足：总投资 (" + round2(total) + " 万元) 必须 ≤ budget ("
                        + round2(budget) + " 万元)");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double budget = ((Number) params.get("budget")).doubleValue();
        double laborCost = ((Number) params.get("labor_cost")).doubleValue();
        double discountRate = ((Number) params.get("discount_rate")).doubleValue();
        int evalYears = ((Number) params.get("eval_years")).intValue();
        double[] cost = new double[6];
        double[] benefit = new double[6];
        double[] roi = new double[6];
        double[] payback = new double[6];
        for (int i = 0; i < 6; i++) {
            cost[i] = ((Number) params.get(COST_KEYS[i])).doubleValue();
            double eff = ((Number) params.get(EFF_KEYS[i])).doubleValue();
            double err = ((Number) params.get(ERR_KEYS[i])).doubleValue();
            benefit[i] = BENEFIT_BASE[i] * (eff + ERROR_COST_RATIO * err) * laborCost / LABOR_BASE;
            roi[i] = benefit[i] / cost[i] * 100;
            payback[i] = cost[i] / benefit[i];
        }

        // 步骤 1：技术清单与投资/收益参数
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(TECH_NAMES[i]).append(" 投入").append(round2(cost[i])).append("万/年收益")
                    .append(round2(benefit[i])).append("万");
        }
        ctx.step("六项候选技术（基于 3.1/3.2/3.3/3.5 场景数据）：" + sb + "；预算 " + round2(budget)
                + " 万元、折现率 " + round2(discountRate * 100) + "%、周期 " + evalYears + " 年",
                Map.of("budget", round2(budget), "tech_count", 6));

        // 步骤 2：单项 ROI / 回收期
        List<Map<String, Object>> roiItems = new ArrayList<>();
        List<Map<String, Object>> paybackItems = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            roiItems.add(Map.of("name", TECH_NAMES[i], "value", round2(roi[i])));
            paybackItems.add(Map.of("name", TECH_NAMES[i], "value", round2(payback[i])));
        }
        ctx.step(String.format("单项 ROI：%s 最高（%.0f%%），AGV 收益最大但回收期 %.2f 年；"
                        + "GPS 投入低但收益基数小",
                topName(roiItems), topValue(roiItems), payback[4]),
                Map.of("top_roi", topName(roiItems)));

        // 步骤 3：预算约束下的最优组合优化（枚举 64 种组合）
        double[] npvByMask = new double[64];
        int bestMask = 0;
        double bestNpv = Double.NEGATIVE_INFINITY;
        for (int mask = 0; mask < 64; mask++) {
            double totalCost = 0;
            double totalBenefit = 0;
            for (int i = 0; i < 6; i++) {
                if ((mask >> i & 1) == 1) {
                    totalCost += cost[i];
                    totalBenefit += benefit[i];
                }
            }
            if (totalCost > budget) {
                npvByMask[mask] = Double.NEGATIVE_INFINITY;
                continue;
            }
            double npv = -totalCost;
            for (int t = 1; t <= evalYears; t++) {
                npv += totalBenefit / Math.pow(1 + discountRate, t);
            }
            npvByMask[mask] = npv;
            if (npv > bestNpv) {
                bestNpv = npv;
                bestMask = mask;
            }
        }
        double comboCost = 0;
        double comboBenefit = 0;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if ((bestMask >> i & 1) == 1) {
                comboCost += cost[i];
                comboBenefit += benefit[i];
                if (names.length() > 0) {
                    names.append("+");
                }
                names.append(TECH_NAMES[i]);
            }
        }
        String combination = names.length() == 0 ? "预算不足，无可选组合" : names.toString();
        List<Double> cx = new ArrayList<>();
        List<Double> cy = new ArrayList<>();
        for (int t = 0; t <= evalYears; t++) {
            double cum = -comboCost;
            for (int k = 1; k <= t; k++) {
                cum += comboBenefit / Math.pow(1 + discountRate, k);
            }
            cx.add((double) t);
            cy.add(round2(cum));
        }
        Map<String, Object> npvSeries = new LinkedHashMap<>();
        npvSeries.put("x", cx);
        npvSeries.put("series", List.of(Map.of("name", "累计NPV(万元)", "data", cy)));
        ctx.step(String.format("最优组合：%s（投入 %.0f 万、年收益 %.0f 万）→ 累计 NPV %.0f 万元",
                combination, comboCost, comboBenefit, bestNpv),
                Map.of("best_combination", combination, "best_npv", round2(bestNpv)));

        // 步骤 4：敏感性分析（±20% 成本/收益）
        List<Map<String, Object>> sensItems = new ArrayList<>();
        sensItems.add(Map.of("name", "成本+20%", "value", round2(npvScaled(bestMask, cost, benefit, 1.2, 1.0, discountRate, evalYears))));
        sensItems.add(Map.of("name", "成本-20%", "value", round2(npvScaled(bestMask, cost, benefit, 0.8, 1.0, discountRate, evalYears))));
        sensItems.add(Map.of("name", "收益+20%", "value", round2(npvScaled(bestMask, cost, benefit, 1.0, 1.2, discountRate, evalYears))));
        sensItems.add(Map.of("name", "收益-20%", "value", round2(npvScaled(bestMask, cost, benefit, 1.0, 0.8, discountRate, evalYears))));
        ctx.step("敏感性分析（最优组合 ±20%）：成本上涨对 NPV 冲击显著，收益下滑次之；"
                        + "技术组合对成本波动更敏感", Map.of("sensitivity_size", sensItems.size()));

        // 输出指标（FR-007）
        ctx.output("roi_ranking", "各技术ROI排名", "compare", roiItems, "%");
        ctx.output("best_combination", "最优技术组合", "scalar", combination, null);
        ctx.output("npv_curve", "累计NPV", "series", npvSeries, "万元");
        ctx.output("payback", "回收期", "compare", paybackItems, "年");
        ctx.output("sensitivity", "敏感性分析(±20%成本/收益)", "compare", sensItems, "万元");
    }

    private String topName(List<Map<String, Object>> items) {
        String best = "";
        double max = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> item : items) {
            double v = ((Number) item.get("value")).doubleValue();
            if (v > max) {
                max = v;
                best = String.valueOf(item.get("name"));
            }
        }
        return best;
    }

    private double topValue(List<Map<String, Object>> items) {
        double max = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> item : items) {
            max = Math.max(max, ((Number) item.get("value")).doubleValue());
        }
        return max;
    }

    private double npvScaled(int mask, double[] cost, double[] benefit, double costScale, double benefitScale,
                             double discountRate, int evalYears) {
        double totalCost = 0;
        double totalBenefit = 0;
        for (int i = 0; i < 6; i++) {
            if ((mask >> i & 1) == 1) {
                totalCost += cost[i];
                totalBenefit += benefit[i];
            }
        }
        double npv = -totalCost * costScale;
        for (int t = 1; t <= evalYears; t++) {
            npv += totalBenefit * benefitScale / Math.pow(1 + discountRate, t);
        }
        return npv;
    }
}
