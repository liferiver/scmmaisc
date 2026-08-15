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
 * 全球供应链区位配置仿真执行器（T030，CH10-001）。
 * 模型：6 个候选区位（劳动力/税率/LPI/政治风险/响应天数画像）× 用户参数（劳动力指数/关税/汇率
 * 波动/所得税/DC 数量/产品类型），测算总成本、税后利润、风险加权成本与响应时间；
 * 标准化产品可集中生产但承担关税，本地化产品靠近市场、关税大幅下降。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class LocationExecutor implements ScenarioExecutor {

    private static final Set<String> LOCATIONS = Set.of("china", "vietnam", "india", "mexico", "eastern_europe", "usa");
    private static final Set<String> PRODUCT_TYPES = Set.of("standardized", "localized");

    /** 区位画像：{劳动力指数, 所得税率, LPI, 政治风险, 基础响应天数} */
    private static final Map<String, double[]> PROFILES = Map.of(
            "china", new double[]{1.0, 0.25, 3.7, 0.08, 3.0},
            "vietnam", new double[]{0.6, 0.20, 3.0, 0.15, 7.0},
            "india", new double[]{0.55, 0.22, 3.0, 0.20, 9.0},
            "mexico", new double[]{0.7, 0.30, 2.9, 0.18, 6.0},
            "eastern_europe", new double[]{0.9, 0.19, 3.4, 0.12, 5.0},
            "usa", new double[]{1.8, 0.21, 3.9, 0.06, 2.0});
    private static final Map<String, String> NAMES = Map.of(
            "china", "中国", "vietnam", "越南", "india", "印度",
            "mexico", "墨西哥", "eastern_europe", "东欧", "usa", "美国");
    private static final List<String> LOCATION_ORDER = List.of(
            "china", "vietnam", "india", "mexico", "eastern_europe", "usa");

    @Override
    public String engineKey() {
        return "location";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        String location = enumParam(params, "production_location", LOCATIONS, errors);
        Integer dcCount = intParam(params, "dc_count", 1, 10, errors);
        String productType = enumParam(params, "product_type", PRODUCT_TYPES, errors);
        Double tariff = doubleParam(params, "tariff_rate", 0, 0.25, errors);
        Double fx = doubleParam(params, "fx_volatility", 0.02, 0.15, errors);
        Double labor = doubleParam(params, "labor_cost_index", 0.5, 2.0, errors);
        Double tax = doubleParam(params, "tax_rate", 0.1, 0.35, errors);
        if (errors.isEmpty() && location != null && dcCount != null && productType != null
                && tariff != null && fx != null && labor != null && tax != null) {
            // 约束 min_two_sites：至少 2 个区域配送中心分散风险
            if (dcCount < 2) {
                errors.add("min_two_sites 约束不满足：至少选择 2 个及以上区域配送中心以分散风险");
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
        String location = String.valueOf(params.get("production_location"));
        int dcCount = ((Number) params.get("dc_count")).intValue();
        String productType = String.valueOf(params.get("product_type"));
        double tariff = ((Number) params.get("tariff_rate")).doubleValue();
        double fx = ((Number) params.get("fx_volatility")).doubleValue();
        double labor = ((Number) params.get("labor_cost_index")).doubleValue();
        double tax = ((Number) params.get("tax_rate")).doubleValue();

        double revenue = 50.0;                 // 年营收（亿元）
        boolean localized = "localized".equals(productType);
        double dcCost = 0.15 * dcCount;        // DC 建设运营成本（亿元）
        double fxCost = revenue * fx * 0.4;    // 汇率风险对冲成本

        // 步骤 1：候选区位画像
        double[] sel = PROFILES.get(location);
        ctx.step(String.format("候选区位画像：%s（劳动力指数 %.1f、所得税 %.0f%%、LPI %.1f、政治风险 %.0f%%）；产品类型 %s，DC %d 个",
                NAMES.get(location), sel[0], sel[1] * 100, sel[2], sel[3] * 100,
                localized ? "区域本地化" : "全球标准化", dcCount),
                Map.of("location", NAMES.get(location), "dc_count", dcCount));

        // 步骤 2：各区位总成本测算
        List<Map<String, Object>> costItems = new ArrayList<>();
        List<Map<String, Object>> profitItems = new ArrayList<>();
        List<Map<String, Object>> responseItems = new ArrayList<>();
        double[] costs = new double[LOCATION_ORDER.size()];
        for (int i = 0; i < LOCATION_ORDER.size(); i++) {
            String key = LOCATION_ORDER.get(i);
            double[] p = PROFILES.get(key);
            double laborI = key.equals(location) ? labor : p[0];
            double taxI = key.equals(location) ? tax : p[1];
            double production = revenue * 0.4 + revenue * 0.3 * laborI;              // 材料 40% + 人工 30%×指数
            double tariffI = revenue * tariff * (localized ? 0.3 : 1.0);             // 本地化生产关税大降
            double riskI = revenue * p[3] * 0.3;                                     // 政治/运营风险成本
            double total = production + tariffI + dcCost + fxCost + riskI;
            costs[i] = total;
            double profit = (revenue - total) * (1 - taxI);
            double response = Math.max(1, p[4] + (6 - dcCount) * 0.8);               // DC 越多响应越快
            costItems.add(Map.of("name", NAMES.get(key), "value", round2(total)));
            profitItems.add(Map.of("name", NAMES.get(key), "value", round2(profit)));
            responseItems.add(Map.of("name", NAMES.get(key), "value", round2(response)));
        }
        ctx.step(String.format("成本测算（亿元）：%s（人工 %.1f×30%%+材料 40%%+关税 %.1f+DC %.1f+汇率 %.1f+风险）",
                NAMES.get(location), labor, revenue * tariff * (localized ? 0.3 : 1.0), dcCost, fxCost, revenue * sel[3] * 0.3),
                Map.of("selected_total_cost", round2(costs[LOCATION_ORDER.indexOf(location)])));

        // 步骤 3：税后利润与风险加权成本
        int selIdx = LOCATION_ORDER.indexOf(location);
        double selTax = tax;
        double selProfit = (revenue - costs[selIdx]) * (1 - selTax);
        double riskWeighted = costs[selIdx] * (1 + sel[3] * 0.5);
        ctx.step(String.format("税后利润 %.2f 亿元（所得税 %.0f%%）；风险加权成本 %.2f 亿元（风险系数 %.0f%% × 0.5）",
                selProfit, selTax * 100, riskWeighted, sel[3] * 100),
                Map.of("after_tax_profit", round2(selProfit), "risk_weighted_cost", round2(riskWeighted)));

        // 步骤 4：响应时间与关税空间
        double responseSel = Math.max(1, sel[4] + (6 - dcCount) * 0.8);
        double tariffSaving = revenue * tariff * (localized ? 0.7 : 0.0);           // 本地化可避免的关税
        ctx.step(String.format("供应链响应时间 %.0f 天；%s：关税优化空间 %.2f 亿元（避免 %.0f%% 关税）",
                responseSel, localized ? "本地化生产" : "标准化集中生产", tariffSaving, tariff * 100),
                Map.of("response_time", round2(responseSel), "tariff_saving", round2(tariffSaving)));

        // 步骤 5：结论
        int bestIdx = 0;
        for (int i = 1; i < costs.length; i++) {
            if (costs[i] < costs[bestIdx]) {
                bestIdx = i;
            }
        }
        String advice = selIdx == bestIdx
                ? "当前区位总成本最优，建议保持"
                : String.format("测算显示 %s 总成本更低（%.1f 亿元），可评估迁移收益", NAMES.get(LOCATION_ORDER.get(bestIdx)), costs[bestIdx]);
        ctx.step(String.format("结论：%s（多区位 + %d 个 DC 分散风险）", advice, dcCount),
                Map.of("advice", advice, "lowest_cost_location", NAMES.get(LOCATION_ORDER.get(bestIdx))));

        // 输出指标（FR-007）
        ctx.output("total_cost_compare", "各方案总成本", "compare", costItems, "亿元");
        ctx.output("after_tax_profit", "税后利润", "compare", profitItems, "亿元");
        ctx.output("risk_weighted_cost", "风险加权成本", "scalar", round2(riskWeighted), "亿元");
        ctx.output("response_time_compare", "供应链响应时间", "compare", responseItems, "天");
        ctx.output("tariff_saving", "关税优化空间", "scalar", round2(tariffSaving), "亿元");
    }
}
