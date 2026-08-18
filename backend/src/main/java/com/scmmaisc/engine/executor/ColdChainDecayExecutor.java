package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.boolParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 冷链物流质量衰减模型仿真执行器（T062，CH11-008）。
 * 模型（Arrhenius 简化）：五个环节（预冷/冷藏运输/冷库/配送/货架）逐小时温度仿真——名义温度
 * ±噪声、配送前装卸暴露（loading_exposure_minutes 分钟处于环境温度）、制冷设备故障概率
 * （equipment_failure_rate，TTI 标签可将故障暴露缩短 30%）→ 质量衰减速率 ∝ 温度敏感系数 ×
 * 偏离最适温区的幅度（超温加倍）→ 剩余货架期 = 初始货架期 × 剩余质量；对比严格温控 / 断链 2h /
 * 多次小幅波动三种方案，识别断链风险点（heatmap）。
 */
@Component
public class ColdChainDecayExecutor implements ScenarioExecutor {

    private static final String[] STAGES = {"预冷", "冷藏运输", "冷库", "配送", "货架展示"};
    private static final double[] STAGE_OFFSET = {-1.5, 0.0, 0.5, 1.0, 1.5}; // 相对最适中点的名义偏离
    private static final double[] BASE_RATE = {0.004, 0.003, 0.005, 0.006, 0.005}; // 各产品类基础衰减/小时
    private static final double[] SENS_MULT = {1.0, 0.9, 1.2, 1.5, 1.4};           // 温敏乘子
    private static final String[] PRODUCT_NAMES = {"生鲜果蔬", "冷冻肉", "冰淇淋", "疫苗", "血液制品"};

    @Override
    public String engineKey() {
        return "cold-chain-decay";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "product_type", Set.of("fresh", "frozen", "icecream", "vaccine", "blood"), errors);
        doubleParam(params, "temp_min", -30, 5, errors);
        Double tMax = doubleParam(params, "temp_max", 0, 15, errors);
        Double tMin = params.get("temp_min") instanceof Number n ? n.doubleValue() : null;
        doubleParam(params, "sensitivity", 1, 10, errors);
        Double shelfLife = doubleParam(params, "initial_shelf_life", 5, 90, errors);
        doubleParam(params, "precool_hours", 0.5, 6, errors);
        doubleParam(params, "transport_hours", 4, 72, errors);
        doubleParam(params, "storage_hours", 12, 168, errors);
        doubleParam(params, "delivery_hours", 1, 12, errors);
        doubleParam(params, "shelf_hours", 24, 240, errors);
        doubleParam(params, "loading_exposure_minutes", 5, 60, errors);
        doubleParam(params, "loading_ambient_temp", 20, 40, errors);
        doubleParam(params, "equipment_failure_rate", 0.001, 0.02, errors);
        boolParam(params, "use_tti", errors);
        if (errors.isEmpty() && shelfLife != null && shelfLife < 5) {
            errors.add("shelf_life_ok 约束不满足：到达时剩余货架期需大于规定天数（初始货架期 ≥ 5 天）");
        }
        if (errors.isEmpty() && tMin != null && tMax != null && tMin >= tMax) {
            errors.add("temp_range_ok 约束不满足：最适温度区间需合法（T_min < T_max）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String product = String.valueOf(params.get("product_type"));
        int pIdx = List.of("fresh", "frozen", "icecream", "vaccine", "blood").indexOf(product);
        double tMin = ((Number) params.get("temp_min")).doubleValue();
        double tMax = ((Number) params.get("temp_max")).doubleValue();
        double sens = ((Number) params.get("sensitivity")).doubleValue();
        double shelfLife = ((Number) params.get("initial_shelf_life")).doubleValue();
        double[] hours = {((Number) params.get("precool_hours")).doubleValue(),
                ((Number) params.get("transport_hours")).doubleValue(),
                ((Number) params.get("storage_hours")).doubleValue(),
                ((Number) params.get("delivery_hours")).doubleValue(),
                ((Number) params.get("shelf_hours")).doubleValue()};
        double exposureH = ((Number) params.get("loading_exposure_minutes")).doubleValue() / 60.0;
        double ambient = ((Number) params.get("loading_ambient_temp")).doubleValue();
        double failRate = ((Number) params.get("equipment_failure_rate")).doubleValue();
        boolean tti = Boolean.TRUE.equals(params.get("use_tti"));

        double mid = (tMin + tMax) / 2;
        double baseRate = BASE_RATE[pIdx] * SENS_MULT[pIdx];
        int totalH = 0;
        for (double h : hours) {
            totalH += (int) Math.round(h);
        }

        // 步骤 1：产品与温区设定
        ctx.step(String.format("%s（温敏乘子 ×%.1f）：最适温区 [%.0f℃, %.0f℃]，敏感系数 %.1f，"
                        + "初始货架期 %.0f 天，TTI 标签 %s",
                PRODUCT_NAMES[pIdx], SENS_MULT[pIdx], tMin, tMax, sens, shelfLife, tti ? "启用" : "未启用"),
                Map.of("product_type", product, "temp_range", tMin + "~" + tMax + "℃",
                        "initial_shelf_life", round2(shelfLife)));

        // 步骤 2：全程逐小时温度仿真（环节温度 + 装卸暴露 + 设备故障）
        List<Double> x = new ArrayList<>();
        List<Double> temps = new ArrayList<>();
        List<Double> quals = new ArrayList<>();
        double quality = 100;
        double[] stageExceedH = new double[5];
        double[] stageFailH = new double[5];
        int h = 0;
        for (int s = 0; s < 5; s++) {
            int stageH = (int) Math.round(hours[s]);
            boolean failure = false;
            int failRemain = 0;
            for (int j = 0; j < stageH; j++) {
                double temp = mid + STAGE_OFFSET[s] + ctx.random().nextGaussian() * 0.8;
                // 配送前装卸暴露（断链关键点）：环境温度持续 exposureH 小时
                if (s == 3 && j == 0) {
                    temp = ambient;
                    int expH = Math.max(1, (int) Math.round(exposureH));
                    for (int e = 0; e < expH; e++) {
                        temps.add(round2(temp));
                        x.add((double) (h + e));
                        quality = decay(quality, ambient, mid, sens, baseRate, tMin, tMax, 1);
                        quals.add(round2(quality));
                    }
                    h += expH;
                }
                // 制冷设备故障（运输/冷库环节，TTI 缩短暴露 30%）
                if ((s == 1 || s == 2) && !failure && ctx.random().nextDouble() < failRate) {
                    failure = true;
                    failRemain = 2;
                    stageFailH[s] += 2 * (tti ? 0.7 : 1.0);
                }
                if (failure && failRemain > 0) {
                    temp = tti ? ambient * 0.7 + mid * 0.3 : ambient;
                    failRemain--;
                    if (failRemain == 0) {
                        failure = false;
                    }
                }
                temps.add(round2(temp));
                x.add((double) h);
                quality = decay(quality, temp, mid, sens, baseRate, tMin, tMax, 1);
                quals.add(round2(quality));
                if (temp < tMin || temp > tMax) {
                    stageExceedH[s]++;
                }
                h++;
            }
        }
        ctx.step(String.format("全程 %d 小时温度仿真完成（装卸暴露 %.1f 小时 × %.0f℃，设备故障暴露按%s计）；"
                        + "温度超限累计 %.0f 小时",
                totalH, exposureH, ambient, tti ? "TTI 缩短 30%" : "全额", sum(stageExceedH)),
                Map.of("total_hours", totalH, "exceed_hours", round2(sum(stageExceedH))));

        // 步骤 3：质量衰减（Arrhenius 简化）与剩余货架期
        double remainingShelfLife = shelfLife * quality / 100.0;
        double damageRate = 100 - quality;
        ctx.step(String.format("质量衰减至 %.1f%%（累计衰减速率 ∝ 敏感系数 %.1f × 温度偏离）；"
                        + "剩余货架期 %.1f 天 / 初始 %.0f 天，货损率 %.1f%%",
                quality, sens, remainingShelfLife, shelfLife, damageRate),
                Map.of("quality_end", round2(quality), "remaining_shelf_life", round2(remainingShelfLife)));

        // 步骤 4：断链风险点识别（heatmap）
        List<String> rows = List.of(STAGES);
        List<String> cols = List.of("温度超标", "断链暴露", "设备故障", "时长累积");
        List<List<Double>> grid = new ArrayList<>();
        for (int s = 0; s < 5; s++) {
            double stageH = Math.round(hours[s]);
            List<Double> rowData = new ArrayList<>();
            rowData.add(round2(Math.min(10, stageExceedH[s] / Math.max(1, stageH) * 10)));
            rowData.add(round2(s == 3 ? Math.min(10, exposureH * 2.5) : 0));
            rowData.add(round2(Math.min(10, stageFailH[s] / Math.max(1, stageH) * 10)));
            rowData.add(round2(Math.min(10, stageH / Math.max(1, totalH) * sens * 1.5)));
            grid.add(rowData);
        }
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", rows);
        heatmap.put("columns", cols);
        heatmap.put("data", grid);
        int worst = 0;
        for (int s = 1; s < 5; s++) {
            if (grid.get(s).get(0) + grid.get(s).get(2) > grid.get(worst).get(0) + grid.get(worst).get(2)) {
                worst = s;
            }
        }
        ctx.step(String.format("断链风险点识别：%s环节风险最高（温度超标 %.0f 小时/设备故障暴露 %.1f 小时）",
                STAGES[worst], stageExceedH[worst], stageFailH[worst]),
                Map.of("risk_points", heatmap, "worst_stage", STAGES[worst]));

        // 步骤 5：三种温控方案对比（严格温控 / 断链2h / 多次小幅波动）
        double qStrict = 100;
        double qWave = 100;
        for (int s = 0; s < 5; s++) {
            int stageH = (int) Math.round(hours[s]);
            for (int j = 0; j < stageH; j++) {
                qStrict = decay(qStrict, mid + STAGE_OFFSET[s], mid, sens, baseRate, tMin, tMax, 0.3);
                qWave = decay(qWave, mid + STAGE_OFFSET[s] + 2.5 * Math.sin(j), mid, sens, baseRate, tMin, tMax, 0);
            }
        }
        double qBreak = qStrict;
        for (int e = 0; e < 2; e++) {
            qBreak = decay(qBreak, ambient, mid, sens, baseRate, tMin, tMax, 1);
        }
        List<Map<String, Object>> planCompare = List.of(
                Map.of("name", "严格温控", "value", round2(qStrict)),
                Map.of("name", "断链2小时", "value", round2(qBreak)),
                Map.of("name", "多次小幅波动", "value", round2(qWave)));
        ctx.step(String.format("方案对比——严格温控 %.1f%% / 断链 2 小时 %.1f%% / 多次小幅波动 %.1f%%："
                        + "单次长断链致命（%s），小幅波动损失小但累积不可忽视",
                qStrict, qBreak, qWave, qBreak < qWave ? "断链 2h 质量损失最大" : "波动累积更大"),
                Map.of("plan_compare", planCompare));

        // 输出指标（FR-007）
        ctx.output("temp_curve", "全程温度曲线", "series", series(x, "温度(℃)", temps), "℃");
        ctx.output("quality_decay_curve", "质量衰减曲线", "series", series(x, "剩余质量(%)", quals), "%");
        ctx.output("remaining_shelf_life", "剩余货架期", "scalar", round2(remainingShelfLife), "天");
        ctx.output("risk_points", "断链风险点识别", "heatmap", heatmap, null);
        ctx.output("damage_rate", "货损率", "scalar", round2(damageRate), "%");
        ctx.output("plan_compare", "不同温控方案对比", "compare", planCompare, null);
    }

    /** 单小时质量衰减：速率 = 基础速率 × (1 + 敏感系数×偏离/5)，超温加倍 */
    private double decay(double q, double temp, double mid, double sens, double baseRate,
                         double tMin, double tMax, double noise) {
        double dev = Math.abs(temp - mid) + noise;
        double rate = baseRate * (1 + sens * dev / 5.0) * (temp < tMin || temp > tMax ? 2 : 1);
        return Math.max(5, q * (1 - rate));
    }

    private double sum(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s;
    }
}
