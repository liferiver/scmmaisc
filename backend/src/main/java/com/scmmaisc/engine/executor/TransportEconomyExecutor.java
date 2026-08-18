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
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 运输规模经济与距离经济仿真执行器（T053，CH2-007；SC-010 公式契约）。
 * 模型：单位运输成本 = 固定成本/批量 + 单位变动成本 × 距离（F/Q + C×d）；
 * 规模经济（批量↑ → 单位固定成本↓）、距离经济（C×d 线性）；成本-批量曲线按
 * 1~车辆容量逐点采样，展示满载与零担差异；对比四种运输方式选出最优。
 * 算例 F=500/C=0.1/d=100 → Q=10 时 60 元/吨，Q=20 时 35 元/吨。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class TransportEconomyExecutor implements ScenarioExecutor {

    private static final Set<String> MODES = Set.of("road", "rail", "water", "air");
    private static final List<String> MODE_ORDER = List.of("road", "rail", "water", "air");
    private static final Map<String, String> MODE_NAMES = Map.of(
            "road", "公路", "rail", "铁路", "water", "水运", "air", "航空");

    @Override
    public String engineKey() {
        return "transport-economy";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        if (params.containsKey("od_distances")) {
            matrixParam(params, "od_distances", 1, 20, 1, 10, 3000, errors);
        }
        enumParam(params, "transport_mode", MODES, errors);
        doubleParam(params, "fixed_cost_road", 100, 10000, errors);
        doubleParam(params, "fixed_cost_rail", 100, 10000, errors);
        doubleParam(params, "fixed_cost_water", 100, 10000, errors);
        doubleParam(params, "fixed_cost_air", 100, 10000, errors);
        doubleParam(params, "var_cost_road", 0.01, 5, errors);
        doubleParam(params, "var_cost_rail", 0.01, 5, errors);
        doubleParam(params, "var_cost_water", 0.01, 5, errors);
        doubleParam(params, "var_cost_air", 0.01, 5, errors);
        Double batch = doubleParam(params, "batch_qty", 1, 30, errors);
        Integer capacity = intParam(params, "vehicle_capacity", 5, 30, errors);
        // 约束 load_ok：运量不得超过车辆容量
        if (errors.isEmpty() && batch != null && capacity != null && batch > capacity) {
            errors.add("load_ok 约束不满足：batch_qty (" + batch + ") 不得超过 vehicle_capacity (" + capacity + ")");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double[][] distances = matrixParam(params, "od_distances", 1, 20, 1, 10, 3000, new ArrayList<>());
        if (distances == null) {
            distances = new double[][]{{100.0}}; // 缺省：单 OD 对 100 km
        }
        String mode = String.valueOf(params.get("transport_mode"));
        double batch = ((Number) params.get("batch_qty")).doubleValue();
        int capacity = ((Number) params.get("vehicle_capacity")).intValue();

        double totalDistance = 0;
        for (double[] row : distances) {
            for (double d : row) {
                totalDistance += d;
            }
        }
        double fixed = ((Number) params.get("fixed_cost_" + mode)).doubleValue();
        double var = ((Number) params.get("var_cost_" + mode)).doubleValue();

        // 步骤 1：需求与成本结构
        ctx.step(String.format("运输需求：%d 个 OD 对、总距离 %.1f km；%s 成本结构：固定 %.0f 元/趟 + 变动 %.2f 元/吨公里",
                distances.length, totalDistance, MODE_NAMES.get(mode), fixed, var),
                Map.of("total_distance", round2(totalDistance), "transport_mode", mode));

        // 步骤 2：当前批量下的单位成本（规模经济）
        double unitCost = fixed / batch + var * totalDistance;
        double loadRate = batch / capacity * 100;
        ctx.step(String.format("单位成本 = F/Q + C×d = %.0f/%.1f + %.2f×%.1f = %.2f 元/吨（装载率 %.1f%%）",
                fixed, batch, var, totalDistance, unitCost, loadRate),
                Map.of("unit_cost", round2(unitCost), "load_rate", round2(loadRate)));

        // 步骤 3：单位成本-批量曲线（1~车辆容量，展示规模经济）
        List<Double> x = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for (int q = 1; q <= capacity; q++) {
            x.add((double) q);
            y.add(round2(fixed / q + var * totalDistance));
        }
        Map<String, Object> curve = series(x, "单位成本(元/吨)", y);
        ctx.step(String.format("成本曲线采样 %d 个批量点（1~%d 吨）：批量 10 → %.2f 元/吨，满载 %d → %.2f 元/吨；"
                        + "批量翻倍单位成本显著下降（规模经济）",
                capacity, capacity, fixed / 10 + var * totalDistance, capacity, fixed / capacity + var * totalDistance),
                Map.of("curve_points", capacity));

        // 步骤 4：最优运输方式对比（同批量下四方式成本）
        double[] fixedAll = {
                ((Number) params.get("fixed_cost_road")).doubleValue(),
                ((Number) params.get("fixed_cost_rail")).doubleValue(),
                ((Number) params.get("fixed_cost_water")).doubleValue(),
                ((Number) params.get("fixed_cost_air")).doubleValue()};
        double[] varAll = {
                ((Number) params.get("var_cost_road")).doubleValue(),
                ((Number) params.get("var_cost_rail")).doubleValue(),
                ((Number) params.get("var_cost_water")).doubleValue(),
                ((Number) params.get("var_cost_air")).doubleValue()};
        int bestIdx = 0;
        for (int i = 1; i < 4; i++) {
            if (fixedAll[i] / batch + varAll[i] * totalDistance
                    < fixedAll[bestIdx] / batch + varAll[bestIdx] * totalDistance) {
                bestIdx = i;
            }
        }
        String bestMode = MODE_ORDER.get(bestIdx);
        String conclusion = bestMode.equals(mode)
                ? "当前方式即为最优"
                : String.format("建议改用%s（单位成本更低）", MODE_NAMES.get(bestMode));
        ctx.step(String.format("四方式同批量对比：%s 最优（%.2f 元/吨）→ %s",
                MODE_NAMES.get(bestMode),
                fixedAll[bestIdx] / batch + varAll[bestIdx] * totalDistance, conclusion),
                Map.of("best_mode", bestMode, "best_unit_cost", round2(fixedAll[bestIdx] / batch + varAll[bestIdx] * totalDistance)));

        // 输出指标（FR-007）
        ctx.output("unit_cost", "单位运输成本", "scalar", round2(unitCost), "元/吨");
        ctx.output("cost_curve", "单位成本-批量曲线", "series", curve, "元/吨");
        ctx.output("best_mode", "最优运输方式", "scalar", MODE_NAMES.get(bestMode), null);
        ctx.output("load_rate", "装载率", "gauge", round2(loadRate), "%");
    }
}
