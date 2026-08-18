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
 * 供应链协同创新案例仿真执行器（T052，CH1-008，京东/菜鸟/华为综合对比）。
 * 模型：三模式画像（京东=自建仓配一体/重资产高效率、菜鸟=平台整合社会物流/轻资产大数据调度、
 * 华为=核心自制+非核心外包/铁三角管理）在给定企业场景（规模/订单/城市/时效/资金/供应商集中度）
 * 下测算总成本、配送时效、控制力评分，并按适用条件计算匹配度，推荐最优模式。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class CaseCompareExecutor implements ScenarioExecutor {

    private static final Set<String> TIME_REQUIREMENTS = Set.of("next_day", "two_three_days", "standard");
    private static final List<String> MODELS = List.of("京东模式", "菜鸟模式", "华为模式");

    @Override
    public String engineKey() {
        return "case-compare";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "revenue", 1, 1000, errors);
        intParam(params, "daily_orders", 1000, 1_000_000, errors);
        intParam(params, "cities", 10, 300, errors);
        String timeReq = enumParam(params, "time_requirement", TIME_REQUIREMENTS, errors);
        doubleParam(params, "capital_adequacy", 0, 1, errors);
        doubleParam(params, "supplier_concentration", 0, 1, errors);
        if (errors.isEmpty() && timeReq != null) {
            // 约束 time_ok：至少一种模式满足给定时效标准
            double limit = switch (timeReq) {
                case "next_day" -> 1.0;
                case "two_three_days" -> 3.0;
                default -> 5.0;
            };
            double[] times = {1.0, 1.5, 3.0};   // 京东/菜鸟/华为基础时效（次日达档）
            boolean anyFit = false;
            for (double t : times) {
                if (t <= limit) {
                    anyFit = true;
                }
            }
            if (!anyFit) {
                errors.add("time_ok 约束不满足：所有模式均无法满足时效标准 " + timeReq);
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
        double revenue = ((Number) params.get("revenue")).doubleValue();
        int dailyOrders = ((Number) params.get("daily_orders")).intValue();
        int cities = ((Number) params.get("cities")).intValue();
        String timeReq = String.valueOf(params.get("time_requirement"));
        double capital = ((Number) params.get("capital_adequacy")).doubleValue();
        double concentration = ((Number) params.get("supplier_concentration")).doubleValue();
        String timeName = switch (timeReq) {
            case "next_day" -> "次日达";
            case "two_three_days" -> "2-3天";
            default -> "标准";
        };

        // 步骤 1：企业场景设定
        ctx.step(String.format("企业场景：年营收 %.0f 亿元、日订单 %d 单、覆盖 %d 城、时效要求【%s】、资金充裕度 %.0f%%、供应商集中度 %.0f%%",
                revenue, dailyOrders, cities, timeName, capital * 100, concentration * 100),
                Map.of("revenue", round2(revenue), "daily_orders", dailyOrders,
                        "cities", cities, "time_requirement", timeReq));

        // 步骤 2-4：三模式成本-时效-控制力测算
        double[] cost = new double[3];
        double[] time = new double[3];
        double[] control = {95, 72, 85};
        double[] timeBase = switch (timeReq) {
            case "next_day" -> new double[]{1.0, 1.5, 3.0};
            case "two_three_days" -> new double[]{1.5, 2.5, 4.0};
            default -> new double[]{2.0, 3.5, 5.0};
        };
        // 京东：重资产，仓储建设成本随城市数线性增长
        cost[0] = revenue * 0.12 + cities * 0.01 + dailyOrders * 5e-6;
        // 菜鸟：轻资产，平台调度成本随订单量增长
        cost[1] = revenue * 0.08 + dailyOrders * 4e-6 + cities * 0.004;
        // 华为：制造供应链，成本随供应商集中度上升（管理复杂度）
        cost[2] = revenue * 0.10 + concentration * revenue * 0.02 + cities * 0.002;
        for (int m = 0; m < 3; m++) {
            time[m] = timeBase[m];
        }
        List<Map<String, Object>> costItems = new ArrayList<>();
        List<Map<String, Object>> timeItems = new ArrayList<>();
        List<Map<String, Object>> controlItems = new ArrayList<>();
        for (int m = 0; m < 3; m++) {
            costItems.add(Map.of("name", MODELS.get(m), "value", round2(cost[m])));
            timeItems.add(Map.of("name", MODELS.get(m), "value", round2(time[m])));
            controlItems.add(Map.of("name", MODELS.get(m), "value", control[m]));
        }
        ctx.step(String.format("成本测算（亿元）：京东 %.2f（自建仓配）、菜鸟 %.2f（平台整合）、华为 %.2f（自制+外包）",
                cost[0], cost[1], cost[2]), Map.of("cost_jd", round2(cost[0]), "cost_cn", round2(cost[1]), "cost_hw", round2(cost[2])));
        ctx.step(String.format("时效测算（天）：京东 %.1f、菜鸟 %.1f、华为 %.1f（要求 %s）",
                time[0], time[1], time[2], timeName), Map.of("time_jd", round2(time[0]), "time_cn", round2(time[1]), "time_hw", round2(time[2])));
        ctx.step(String.format("控制力评分（分）：京东 %.0f（自建全链路）、菜鸟 %.0f（平台规则）、华为 %.0f（铁三角管理）",
                control[0], control[1], control[2]), Map.of("control_jd", control[0], "control_cn", control[1], "control_hw", control[2]));

        // 步骤 5：适用条件匹配度
        double scale = Math.min(1, dailyOrders / 500_000.0);
        double coverageScale = Math.min(1, cities / 200.0);
        double timeScore = switch (timeReq) {
            case "next_day" -> 1.0;
            case "two_three_days" -> 0.6;
            default -> 0.3;
        };
        double fitJd = 0.30 * capital + 0.30 * timeScore + 0.20 * scale + 0.20 * coverageScale;
        double fitCn = 0.20 * capital + 0.20 * timeScore + 0.30 * scale + 0.30 * coverageScale;
        double fitHw = 0.25 * concentration + 0.25 * capital + 0.25 * timeScore + 0.25 * scale;
        double[] fit = {fitJd, fitCn, fitHw};
        int best = fitJd >= fitCn ? 0 : 1;
        best = fit[best] >= fitHw ? best : 2;
        double fitScore = fit[best] * 100;
        String advice = switch (best) {
            case 0 -> "京东模式（自建仓配一体）";
            case 1 -> "菜鸟模式（平台整合社会物流）";
            default -> "华为模式（核心自制+非核心外包）";
        };
        ctx.step(String.format("匹配度：京东 %.0f%%、菜鸟 %.0f%%、华为 %.0f%% → 推荐【%s】",
                fitJd * 100, fitCn * 100, fitHw * 100, advice),
                Map.of("fit_jd", round2(fitJd * 100), "fit_cn", round2(fitCn * 100),
                        "fit_hw", round2(fitHw * 100), "recommended", advice));

        // 输出指标（FR-007）
        ctx.output("total_cost", "各模式总成本", "compare", costItems, "亿元");
        ctx.output("delivery_time", "平均配送时效", "compare", timeItems, "天");
        ctx.output("control_score", "物流控制力评分", "compare", controlItems, "分");
        ctx.output("fit_score", "适用条件匹配度", "scalar", round2(fitScore), "%");
    }
}
