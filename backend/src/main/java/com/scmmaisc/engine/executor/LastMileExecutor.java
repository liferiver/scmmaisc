package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.distParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 电商最后一公里配送模式仿真执行器（T028，CH4-006）。
 * 模型：上门（一次妥投率决定二次投递）、快递柜（格口×周转率决定容量）、
 * 驿站（覆盖半径决定步行距离）、众包（按单计费即时配送）四种模式对比；
 * 按消费者偏好分布加权得到混合方案。确定性模型：seed 无关（FR-008）。
 */
@Component
public class LastMileExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "last-mile";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer parcels = intParam(params, "daily_parcels", 500, 50_000, errors);
        Double homeSuccess = doubleParam(params, "home_delivery_success", 0.6, 0.95, errors);
        Integer slots = intParam(params, "locker_slots", 50, 500, errors);
        Double turnover = doubleParam(params, "locker_turnover", 1, 5, errors);
        Double radius = doubleParam(params, "station_radius", 300, 1000, errors);
        Double crowdPrice = doubleParam(params, "crowd_unit_price", 3, 10, errors);
        Map<String, Double> pref = params.containsKey("consumer_preference")
                ? distParam(params, "consumer_preference",
                        Map.of("home", new double[]{0, 1}, "locker", new double[]{0, 1},
                                "station", new double[]{0, 1}, "crowd", new double[]{0, 1}), errors)
                : null; // 可选分布：缺省时由 run() 使用内置偏好
        if (errors.isEmpty() && parcels != null && homeSuccess != null && slots != null
                && turnover != null && radius != null && crowdPrice != null && pref != null) {
            double sum = pref.values().stream().mapToDouble(Double::doubleValue).sum();
            if (sum <= 0) {
                errors.add("consumer_preference 各项权重之和必须大于 0");
            }
            // 约束 satisfaction_4：加权满意度须 ≥ 4.0
            double[] w = normalize(pref);
            double satisfaction = w[0] * (4.6 - (1 - homeSuccess) * 1.5)
                    + w[1] * 4.0 + w[2] * (4.2 - Math.max(0, (radius - 500) / 500.0) * 0.5) + w[3] * 4.3;
            if (satisfaction < 4.0) {
                errors.add(String.format("satisfaction_4 约束不满足：加权满意度 %.2f < 4.0，请提升上门成功率或缩短驿站半径", satisfaction));
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
        int parcels = ((Number) params.get("daily_parcels")).intValue();
        double homeSuccess = ((Number) params.get("home_delivery_success")).doubleValue();
        int slots = ((Number) params.get("locker_slots")).intValue();
        double turnover = ((Number) params.get("locker_turnover")).doubleValue();
        double radius = ((Number) params.get("station_radius")).doubleValue();
        double crowdPrice = ((Number) params.get("crowd_unit_price")).doubleValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> prefRaw = (Map<String, Object>) params.get("consumer_preference");
        if (prefRaw == null) {
            prefRaw = Map.of("home", 0.4, "locker", 0.3, "station", 0.2, "crowd", 0.1); // 缺省偏好
        }
        Map<String, Double> pref = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : prefRaw.entrySet()) {
            pref.put(e.getKey(), ((Number) e.getValue()).doubleValue());
        }

        // 四种模式：单均成本 / 时效 / 满意度
        double homeCost = 6.0 + (1 - homeSuccess) * 4.0;              // 二次投递成本
        double homeTime = 24 * (1 + (1 - homeSuccess) * 0.5);         // 小时，二次投递延误
        double homeSat = 4.6 - (1 - homeSuccess) * 1.5;

        double lockerCapacity = slots * turnover;                     // 格口日吞吐
        double lockerUtil = Math.min(parcels / lockerCapacity, 2.0);
        double lockerCost = 2.5 + Math.max(0, lockerUtil - 0.8) * 3.0; // 超容后外溢成本
        double lockerTime = 12 + Math.max(0, lockerUtil - 0.8) * 6;
        double lockerSat = 4.0 - Math.max(0, lockerUtil - 0.8) * 1.0;

        double stationCost = 3.0;
        double stationTime = 18;
        double stationSat = 4.2 - Math.max(0, (radius - 500) / 500.0) * 0.5;

        double crowdCost = crowdPrice;
        double crowdTime = 6;
        double crowdSat = 4.3;

        // 步骤 1：末端包裹到达
        ctx.step(String.format("末端包裹到达：日均 %d 件；上门一次妥投率 %.0f%%，快递柜 %d 格口×%.1f 次周转，驿站半径 %.0f 米",
                parcels, homeSuccess * 100, slots, turnover, radius),
                Map.of("daily_parcels", parcels));

        // 步骤 2：四模式评估
        ctx.step(String.format("上门：%.1f 元/单、%.0f 小时、满意度 %.1f（二次投递率 %.0f%%）",
                homeCost, homeTime, homeSat, (1 - homeSuccess) * 100),
                Map.of("home_cost", round2(homeCost), "home_time", round2(homeTime), "home_satisfaction", round2(homeSat)));
        ctx.step(String.format("快递柜：柜容量利用率 %.0f%%，%.1f 元/单、%.0f 小时、满意度 %.1f",
                lockerUtil * 100, lockerCost, lockerTime, lockerSat),
                Map.of("locker_utilization", round2(lockerUtil * 100), "locker_cost", round2(lockerCost)));
        ctx.step(String.format("驿站：%.1f 元/单、%.0f 小时、满意度 %.1f；众包：%.1f 元/单、%.0f 小时、满意度 %.1f",
                stationCost, stationTime, stationSat, crowdCost, crowdTime, crowdSat),
                Map.of("station_cost", round2(stationCost), "crowd_cost", round2(crowdCost)));

        // 步骤 3：消费者偏好加权（混合方案）
        double[] w = normalize(pref);
        double blendCost = w[0] * homeCost + w[1] * lockerCost + w[2] * stationCost + w[3] * crowdCost;
        double blendTime = w[0] * homeTime + w[1] * lockerTime + w[2] * stationTime + w[3] * crowdTime;
        double blendSat = w[0] * homeSat + w[1] * lockerSat + w[2] * stationSat + w[3] * crowdSat;
        double redeliveryRate = w[0] * (1 - homeSuccess);
        ctx.step(String.format("偏好加权（上门 %.0f%%/柜 %.0f%%/站 %.0f%%/众包 %.0f%%）：混合单均 %.1f 元、时效 %.0f 小时、满意度 %.1f",
                w[0] * 100, w[1] * 100, w[2] * 100, w[3] * 100, blendCost, blendTime, blendSat),
                Map.of("blend_cost", round2(blendCost), "blend_time", round2(blendTime),
                        "blend_satisfaction", round2(blendSat), "redelivery_rate", round2(redeliveryRate * 100)));

        // 步骤 4：最优模式组合（满意度优先下成本最低）
        String bestMode = "上门";
        double bestSat = homeSat;
        double[] sats = {homeSat, lockerSat, stationSat, crowdSat};
        String[] names = {"上门", "快递柜", "驿站", "众包"};
        for (int i = 1; i < names.length; i++) {
            if (sats[i] > bestSat) {
                bestSat = sats[i];
                bestMode = names[i];
            }
        }
        ctx.step(String.format("最优模式组合：%s（满意度 %.1f）——结合偏好可进一步提升体验", bestMode, bestSat),
                Map.of("best_mode_mix", bestMode));

        // 步骤 5：结论
        ctx.step("结论：人口密度高的区域应提高柜/站比例，郊区保留上门，二次投递率是体验关键",
                Map.of("redelivery_rate", round2(redeliveryRate * 100)));

        // 输出指标（FR-007）
        ctx.output("unit_cost_compare", "各模式单均成本", "compare",
                List.of(Map.of("name", "上门", "value", round2(homeCost)),
                        Map.of("name", "快递柜", "value", round2(lockerCost)),
                        Map.of("name", "驿站", "value", round2(stationCost)),
                        Map.of("name", "众包", "value", round2(crowdCost))),
                "元/单");
        ctx.output("delivery_time_compare", "平均配送时效", "compare",
                List.of(Map.of("name", "上门", "value", round2(homeTime)),
                        Map.of("name", "快递柜", "value", round2(lockerTime)),
                        Map.of("name", "驿站", "value", round2(stationTime)),
                        Map.of("name", "众包", "value", round2(crowdTime))),
                "小时");
        ctx.output("satisfaction_compare", "消费者满意度", "compare",
                List.of(Map.of("name", "上门", "value", round2(homeSat)),
                        Map.of("name", "快递柜", "value", round2(lockerSat)),
                        Map.of("name", "驿站", "value", round2(stationSat)),
                        Map.of("name", "众包", "value", round2(crowdSat))),
                "分");
        ctx.output("redelivery_rate", "二次投递率", "scalar", round2(redeliveryRate * 100), "%");
        ctx.output("best_mode_mix", "最优模式组合", "scalar", bestMode, null);
    }

    private static double[] normalize(Map<String, Double> pref) {
        double sum = pref.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            sum = 1;
        }
        return new double[]{
                pref.getOrDefault("home", 0.0) / sum,
                pref.getOrDefault("locker", 0.0) / sum,
                pref.getOrDefault("station", 0.0) / sum,
                pref.getOrDefault("crowd", 0.0) / sum
        };
    }
}
