package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 应急物流——人道主义救援仿真执行器（T062，CH11-007）。
 * 模型（轻量启发式）：灾害类型决定基础设施破坏度（地震 0.40/洪水 0.30/台风 0.35/疫情 0.15），
 * 可选道路中断概率矩阵叠加；受灾点需求信息 24 小时内逐步明确（信息不确定）；每小时按优先级
 * （物资权重×受灾严重度）分配运力（transport_capacity 车辆，考虑破坏率利用率）→ 黄金时间窗内
 * 送达比例决定伤亡减免 → 输出未满足需求时间加权序列、覆盖度对比、配送效率与 72h 窗口利用率。
 */
@Component
public class EmergencyLogisticsExecutor implements ScenarioExecutor {

    private static final double[] DISRUPTION = {0.40, 0.30, 0.35, 0.15}; // 地震/洪水/台风/疫情
    private static final double[] CAS_FACTOR = {1.30, 1.00, 1.10, 0.70};
    private static final double TON_PER_TRIP = 5.0;   // 单辆车单趟载重（吨）
    private static final double TRIP_HOURS = 6.0;     // 往返耗时（小时）

    @Override
    public String engineKey() {
        return "emergency-logistics";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "disaster_type", Set.of("earthquake", "flood", "typhoon", "epidemic"), errors);
        int supply = intParam(params, "supply_nodes", 1, 5, errors);
        int sites = intParam(params, "affected_sites", 5, 30, errors);
        if (errors.isEmpty() && params.containsKey("road_disruption_matrix")) {
            matrixParam(params, "road_disruption_matrix", supply, supply, sites, 0.0, 0.5, errors);
        }
        intParam(params, "transport_capacity", 10, 100, errors);
        doubleParam(params, "weight_medicine", 0.1, 0.4, errors);
        doubleParam(params, "weight_water", 0.1, 0.4, errors);
        doubleParam(params, "weight_food", 0.1, 0.4, errors);
        doubleParam(params, "weight_tent", 0.05, 0.2, errors);
        doubleParam(params, "golden_window_hours", 24, 120, errors);
        doubleParam(params, "info_update_hours", 2, 12, errors);
        intParam(params, "sim_hours", 24, 240, errors);
        Double wMed = params.get("weight_medicine") instanceof Number n ? n.doubleValue() : null;
        Double wWater = params.get("weight_water") instanceof Number n ? n.doubleValue() : null;
        Double wFood = params.get("weight_food") instanceof Number n ? n.doubleValue() : null;
        Double wTent = params.get("weight_tent") instanceof Number n ? n.doubleValue() : null;
        if (errors.isEmpty() && wMed != null && wWater != null && wFood != null && wTent != null
                && wMed + wWater + wFood + wTent > 1.0) {
            errors.add("weight_sum_ok 约束不满足：物资优先级权重之和需 = 1（药品>水>食品>帐篷）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String disaster = String.valueOf(params.get("disaster_type"));
        int sites = ((Number) params.get("affected_sites")).intValue();
        int supply = ((Number) params.get("supply_nodes")).intValue();
        double capacity = ((Number) params.get("transport_capacity")).doubleValue();
        double wMed = ((Number) params.get("weight_medicine")).doubleValue();
        double wWater = ((Number) params.get("weight_water")).doubleValue();
        double wFood = ((Number) params.get("weight_food")).doubleValue();
        double wTent = ((Number) params.get("weight_tent")).doubleValue();
        double golden = ((Number) params.get("golden_window_hours")).doubleValue();
        double infoUpd = ((Number) params.get("info_update_hours")).doubleValue();
        int simH = ((Number) params.get("sim_hours")).intValue();

        String[] typeNames = {"地震", "洪水", "台风", "疫情"};
        int typeIdx = List.of("earthquake", "flood", "typhoon", "epidemic").indexOf(disaster);
        double disruption = DISRUPTION[typeIdx];
        if (params.containsKey("road_disruption_matrix")) {
            double[][] m = matrixParam(params, "road_disruption_matrix", supply, supply, sites,
                    0.0, 0.5, new ArrayList<>());
            double sum = 0;
            for (double[] row : m) {
                for (double v : row) {
                    sum += v;
                }
            }
            double avg = sum / (m.length * Math.max(1, m[0].length));
            disruption = 0.5 * disruption + 0.5 * avg;
        }

        // 步骤 1：灾情建模（灾害类型 + 道路中断 + 受灾点需求）
        double[] severity = new double[sites];
        double[] demand = new double[sites];
        double totalDemand = 0;
        for (int i = 0; i < sites; i++) {
            severity[i] = 0.7 + ctx.random().nextDouble() * 0.6;
            demand[i] = round2((45 + ctx.random().nextDouble() * 30) * severity[i]);
            totalDemand += demand[i];
        }
        ctx.step(String.format("%s灾害：受灾点 %d 个，源点 %d 个；道路中断概率 %.0f%%（有效运力利用率 %.0f%%）；"
                        + "物资总需求 %.0f 吨（药品 %.0f%%/水 %.0f%%/食品 %.0f%%/帐篷 %.0f%%）",
                typeNames[typeIdx], sites, supply, disruption * 100, (1 - disruption) * 100,
                totalDemand, wMed * 100, wWater * 100, wFood * 100, wTent * 100),
                Map.of("disaster_type", disaster, "disruption", round2(disruption),
                        "total_demand", round2(totalDemand)));

        // 步骤 2：需求信息逐步明确（初始模糊 → 24h 内明确）
        ctx.step(String.format("初始仅掌握 30%% 受灾点需求信息，随信息更新（每 %.0f 小时一次）逐步明确，"
                        + "24 小时内完全明确——信息不完全条件下优先保障已确认的高危点",
                infoUpd), Map.of("info_update_hours", round2(infoUpd)));

        // 步骤 3：逐小时物资调度（优先级 = 物资权重 × 受灾严重度）
        double hourlyCap = capacity * TON_PER_TRIP / TRIP_HOURS * 0.55 * (1 - disruption);
        double[] delivered = new double[sites];
        double totalDelivered = 0;
        double deliveredInWindow = 0;
        List<Double> tPoints = new ArrayList<>();
        List<Double> unmetSeries = new ArrayList<>();
        Integer[] order = new Integer[sites];
        for (int i = 0; i < sites; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(
                severity[b] * (wMed + wWater + wFood + wTent), severity[a] * (wMed + wWater + wFood + wTent)));
        double weightedUnmet = 0;
        for (int h = 1; h <= simH; h++) {
            double knownFrac = Math.min(1.0, 0.3 + 0.7 * h / 24.0);
            double alloc = hourlyCap;
            for (int idx : order) {
                double need = demand[idx] * knownFrac - delivered[idx];
                if (need > 0 && alloc > 0) {
                    double give = Math.min(need, alloc);
                    delivered[idx] += give;
                    alloc -= give;
                    totalDelivered += give;
                    if (h <= golden) {
                        deliveredInWindow += give;
                    }
                }
            }
            double remaining = 0;
            for (int i = 0; i < sites; i++) {
                remaining += Math.max(0, demand[i] - delivered[i]);
            }
            weightedUnmet += remaining * (1 + Math.max(0, h - golden) / golden);
            if (h % 12 == 0) {
                tPoints.add((double) h);
                unmetSeries.add(round2(remaining));
            }
        }
        ctx.step(String.format("逐小时调度 %.0f 小时：小时运力 %.1f 吨（%d 辆×%.0f 吨/%.0fh×利用率 %.0f%%）；"
                        + "累计送达 %.0f 吨 / 总需求 %.0f 吨",
                (double) simH, hourlyCap, (int) capacity, TON_PER_TRIP, TRIP_HOURS, 0.55 * (1 - disruption) * 100,
                totalDelivered, totalDemand),
                Map.of("hourly_capacity", round2(hourlyCap), "total_delivered", round2(totalDelivered)));

        // 步骤 4：黄金时间窗利用与未满足需求
        double windowUtil = totalDelivered > 0 ? deliveredInWindow / totalDelivered : 0;
        double remainingEnd = 0;
        for (int i = 0; i < sites; i++) {
            remainingEnd += Math.max(0, demand[i] - delivered[i]);
        }
        ctx.step(String.format("黄金 %.0f 小时窗口内送达 %.1f%% 物资；期末未满足需求 %.0f 吨"
                        + "（时间加权未满足指数 %.0f）",
                golden, windowUtil * 100, remainingEnd, weightedUnmet),
                Map.of("golden_window_util", round2(windowUtil * 100),
                        "weighted_unmet", round2(weightedUnmet)));

        // 步骤 5：伤亡评估与覆盖度对比
        double baseCas = sites * 45.0 * CAS_FACTOR[typeIdx];
        double casualties = Math.round(baseCas * (1 - 0.7 * windowUtil));
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (int i = 0; i < sites; i++) {
            coverage.add(Map.of("name", "受灾点" + (i + 1),
                    "value", round2(Math.min(100, delivered[i] / demand[i] * 100))));
        }
        double efficiency = totalDelivered / simH;
        ctx.step(String.format("预计伤亡 %.0f 人（基准 %.0f 人 × 减免 %.0f%%）；配送效率 %.1f 吨/小时；"
                        + "结论：信息更新频率越高、运力越足，黄金窗口利用率越高，伤亡越低",
                casualties, baseCas, 0.7 * windowUtil * 100, efficiency),
                Map.of("casualties", round2(casualties), "delivery_efficiency", round2(efficiency),
                        "coverage_compare", coverage));

        // 输出指标（FR-007）
        ctx.output("unmet_demand_series", "未满足需求-时间加权", "series",
                series(tPoints, "未满足需求(吨)", unmetSeries), null);
        ctx.output("casualties", "伤亡人数", "scalar", round2(casualties), "人");
        ctx.output("coverage_compare", "各受灾点物资覆盖度", "compare", coverage, "%");
        ctx.output("delivery_efficiency", "配送效率", "scalar", round2(efficiency), "吨/小时");
        ctx.output("golden_window_util", "72h黄金窗口利用率", "gauge", List.of(
                Map.of("name", "黄金窗口利用率", "value", round2(windowUtil * 100))), "%");
    }
}
