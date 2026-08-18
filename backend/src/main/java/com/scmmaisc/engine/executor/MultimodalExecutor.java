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
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 多式联运方案枚举与 Pareto 优化执行器（T056，CH5-002）。
 * 模型：按起运地-目的地枚举可行运输方案（纯海运/纯空运/纯铁路/海铁联运/铁公联运/海空联运），
 * 每个方案按段评估成本(元/TEU·km × TEU)、时效(距离/速度+换装时间)、碳排放与可靠性(段连乘)，
 * 多目标打分排序并求 Pareto 前沿；mode_attrs 矩阵参数缺省时使用内置默认属性。
 */
@Component
public class MultimodalExecutor implements ScenarioExecutor {

    private static final Set<String> OD_PAIRS =
            Set.of("shanghai_la", "shenzhen_hamburg", "chongqing_duisburg", "tianjin_singapore");

    /** 各运输方式默认属性：成本(元/TEU·km) / 速度(km/h) / 可靠性 / 碳排放(g/TEU·km)。 */
    private static final double[][] DEFAULT_MODE_ATTRS = {
            {1.2, 35, 0.95, 22},    // 海运
            {20.0, 850, 0.99, 650}, // 空运
            {4.0, 100, 0.97, 30},   // 铁路
            {10.0, 70, 0.93, 115}   // 公路
    };
    private static final String[] MODE_NAMES = {"海运", "空运", "铁路", "公路"};

    /** 各 OD 的可行方案（模式索引序列），0=海运 1=空运 2=铁路 3=公路。 */
    private static final Map<String, int[][]> OD_PLANS = Map.of(
            "shanghai_la", new int[][]{{0}, {1}, {0, 1}},
            "shenzhen_hamburg", new int[][]{{0}, {1}, {2}, {0, 2}, {2, 3}},
            "chongqing_duisburg", new int[][]{{0}, {1}, {2}, {0, 2}, {2, 3}, {3, 2}},
            "tianjin_singapore", new int[][]{{0}, {1}, {0, 1}});

    /** 方案名：单段用方式名，多段用“联运”组合名。 */
    private static String planName(int[] legs) {
        if (legs.length == 1) {
            return MODE_NAMES[legs[0]];
        }
        if (legs[0] == 0 && legs[1] == 1) {
            return "海空联运";
        }
        if (legs[0] == 0 && legs[1] == 2) {
            return "海铁联运";
        }
        if (legs[0] == 2 && legs[1] == 3) {
            return "铁公联运";
        }
        return "公铁联运";
    }

    /** 各 OD 分模式距离(km)：海运/空运/铁路/公路。 */
    private static final Map<String, double[]> OD_DIST = Map.of(
            "shanghai_la", new double[]{10400, 10400, 15000, 0},
            "shenzhen_hamburg", new double[]{19500, 9300, 12400, 800},
            "chongqing_duisburg", new double[]{20000, 10000, 11179, 900},
            "tianjin_singapore", new double[]{4600, 4400, 0, 0});

    @Override
    public String engineKey() {
        return "multimodal";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double tonnage = doubleParam(params, "cargo_tonnage", 1, 500, errors);
        String od = enumParam(params, "od_pair", OD_PAIRS, errors);
        // mode_attrs 为可编辑矩阵参数：仅当显式提供时校验形状（4 行 × 4 列）
        if (params.get("mode_attrs") != null) {
            matrixParam(params, "mode_attrs", 4, 4, 4, 0, 1000, errors);
        }
        Double transferCost = doubleParam(params, "transfer_cost", 1000, 50000, errors);
        Double transferTime = doubleParam(params, "transfer_time", 2, 72, errors);
        Double priority = doubleParam(params, "time_priority", 0, 1, errors);
        if (errors.isEmpty() && tonnage != null && od != null && transferCost != null
                && transferTime != null && priority != null) {
            // 约束 deadline_ok：时效优先级需在合理区间（0=成本优先，1=时效优先）
            if (priority > 1) {
                errors.add("deadline_ok 约束不满足：时效优先级需 ≤ 1（0=绝对成本优先，1=绝对时效优先）");
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
        double tonnage = ((Number) params.get("cargo_tonnage")).doubleValue();
        String od = String.valueOf(params.get("od_pair"));
        double[][] attrs = matrixParam(params, "mode_attrs", 4, 4, 4, 0, 1000, new ArrayList<>());
        if (attrs == null) {
            attrs = DEFAULT_MODE_ATTRS;
        }
        double transferCost = ((Number) params.get("transfer_cost")).doubleValue();
        double transferTime = ((Number) params.get("transfer_time")).doubleValue();
        double priority = ((Number) params.get("time_priority")).doubleValue();

        int teu = Math.max(1, (int) Math.round(tonnage / 15.0));
        int[][] plans = OD_PLANS.get(od);
        double[] distByMode = OD_DIST.get(od);


        // 步骤 1：需求与方式属性
        ctx.step(String.format("货量 %.0f 吨（≈ %d TEU），%s；海运/空运/铁路/公路属性已载入（%s）",
                        tonnage, teu, od, attrs == DEFAULT_MODE_ATTRS ? "内置默认" : "用户矩阵"),
                Map.of("tonnage", tonnage, "teu", teu, "od_pair", od));

        // 步骤 2：方案枚举与计算
        List<Map<String, Object>> scatter = new ArrayList<>();
        List<Map<String, Object>> carbonList = new ArrayList<>();
        double[] costs = new double[plans.length];
        double[] times = new double[plans.length];
        double[] rels = new double[plans.length];
        double[] carbons = new double[plans.length];
        double maxCost = 1, maxTime = 1;
        for (int p = 0; p < plans.length; p++) {
            int[] legs = plans[p];
            double cost = 0, hours = 0, carbon = 0, reliability = 1.0;
            double totalDist = 0;
            for (int leg : legs) {
                double d = distByMode[leg];
                if (d <= 0) {
                    continue;
                }
                double c = attrs[leg][0], speed = attrs[leg][1];
                double rel = Math.min(1.0, Math.max(0.5, attrs[leg][2]));
                double carb = attrs[leg][3];
                cost += c * d * teu;
                hours += d / speed * 24;
                carbon += carb * d * teu;
                reliability *= rel;
                totalDist += d;
            }
            int transfers = legs.length - 1;
            cost += transfers * transferCost;
            hours += transfers * transferTime;
            reliability *= Math.pow(0.98, transfers);
            double days = hours / 24.0;
            costs[p] = cost;
            times[p] = days;
            rels[p] = reliability;
            carbons[p] = round2(carbon / Math.max(1, totalDist) / teu);
            maxCost = Math.max(maxCost, cost);
            maxTime = Math.max(maxTime, days);
            String name = planName(legs);
            scatter.add(Map.of("name", name + "(%.0f天)".formatted(days), "value", round2(cost / 10000)));
            carbonList.add(Map.of("name", name, "value", carbons[p]));
        }
        ctx.step("枚举 %d 个可行方案并计算成本/时效/碳排/可靠性".formatted(plans.length),
                Map.of("plan_count", plans.length, "costs_wan", scatter, "times_days", times));

        // 步骤 3：Pareto 前沿（不存在成本与时效均更优的方案）
        List<Map<String, Object>> pareto = new ArrayList<>();
        for (int p = 0; p < plans.length; p++) {
            boolean dominated = false;
            for (int q = 0; q < plans.length; q++) {
                if (q != p && costs[q] < costs[p] && times[q] < times[p]) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                pareto.add(Map.of("name", "P%d".formatted(p + 1), "value", round2(costs[p] / 10000)));
            }
        }
        ctx.step("Pareto 前沿方案 %d 个：其余方案存在成本与时效均更优的替代".formatted(pareto.size()),
                Map.of("pareto_size", pareto.size(), "pareto", pareto));

        // 步骤 4：多目标打分与推荐（time_priority=1 绝对时效优先，0 绝对成本优先）
        int best = 0;
        double bestScore = Double.MAX_VALUE;
        for (int p = 0; p < plans.length; p++) {
            double score = (costs[p] / maxCost) * (1 - priority) + (times[p] / maxTime) * priority;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        int[] legs = plans[best];
        String bestPlan = "%s：成本 %.1f 万元，时效 %.1f 天，碳排放 %.0f g/TEU·km，可靠性 %.1f%%（时效权重 %.0f%%）"
                .formatted(planName(legs), costs[best] / 10000, times[best],
                        carbons[best], rels[best] * 100, priority * 100);
        ctx.step("推荐方案：" + bestPlan, Map.of("best_index", best + 1, "best_score", round2(bestScore)));

        // 输出指标（FR-007）
        ctx.output("cost_time_scatter", "各方案成本-时效散点", "compare", scatter, null);
        ctx.output("pareto_set", "Pareto最优方案集", "compare", pareto, null);
        ctx.output("carbon_compare", "碳排放对比", "compare", carbonList, "g/TEU·km");
        ctx.output("best_plan", "推荐方案详情", "scalar", bestPlan, null);
    }
}
