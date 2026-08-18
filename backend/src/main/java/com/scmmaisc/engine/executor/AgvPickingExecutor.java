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
 * 智能仓储 AGV 货到人拣选仿真执行器（T054，CH3-005，进阶）。
 * 模型：AGV 单台任务循环（往返搬运+交接）→ 命中率折算有效产能 → 冲突策略与
 * 充电可用性损耗 → 与拣选站产能、订单到达率三者取最小为吞吐量；逐步增加 AGV
 * 数量观察吞吐量 线性增长→边际递减→上限（瓶颈漂移），并给出最优 AGV 数量与
 * 路径冲突次数。确定性模型：seed 无关（FR-008）。
 */
@Component
public class AgvPickingExecutor implements ScenarioExecutor {

    private static final Set<String> CONFLICT_STRATEGIES = Set.of("wait", "detour", "priority");
    private static final double AVG_ROUND_TRIP_M = 40.0;    // 平均往返搬运距离（m）
    private static final double HANDOVER_S = 25.0;           // 取放+站台交接（秒/趟）
    private static final double STATION_CAP = 100.0;         // 单站拣选产能（单/h）
    private static final double CHARGE_H = 1.5;              // 单次充电时长（h）

    @Override
    public String engineKey() {
        return "agv-picking";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double agvCount = doubleParam(params, "agv_count", 5, 200, errors);
        Integer workstations = intParam(params, "workstation_count", 2, 20, errors);
        doubleParam(params, "agv_speed", 0.5, 3.0, errors);
        doubleParam(params, "hit_rate", 0.3, 0.9, errors);
        doubleParam(params, "battery_hours", 4, 10, errors);
        doubleParam(params, "order_arrival_rate", 100, 5000, errors);
        enumParam(params, "conflict_strategy", CONFLICT_STRATEGIES, errors);
        // 约束 capacity_ok：AGV 数量不超过拣选站可容纳上限
        if (errors.isEmpty() && agvCount != null && workstations != null && agvCount > workstations * 25) {
            errors.add("capacity_ok 约束不满足：agv_count (" + agvCount.intValue() + ") 必须 ≤ workstation_count × 25 ("
                    + workstations * 25 + ")");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int agvCount = ((Number) params.get("agv_count")).intValue();
        int workstationCount = ((Number) params.get("workstation_count")).intValue();
        double agvSpeed = ((Number) params.get("agv_speed")).doubleValue();
        double hitRate = ((Number) params.get("hit_rate")).doubleValue();
        double batteryHours = ((Number) params.get("battery_hours")).doubleValue();
        double orderArrivalRate = ((Number) params.get("order_arrival_rate")).doubleValue();
        String conflictStrategy = String.valueOf(params.get("conflict_strategy"));

        double cycleSec = AVG_ROUND_TRIP_M / agvSpeed + HANDOVER_S;
        double tripsPerHour = 3600.0 / cycleSec;
        double ordersPerAgv = tripsPerHour * hitRate;
        double conflictFactor = switch (conflictStrategy) {
            case "detour" -> 0.90;
            case "wait" -> 0.93;
            default -> 0.97;   // priority
        };
        double batteryFactor = batteryHours / (batteryHours + CHARGE_H);
        double capPerAgv = ordersPerAgv * conflictFactor * batteryFactor;
        double agvCap = agvCount * capPerAgv;
        double stationCap = workstationCount * STATION_CAP;
        double plateau = Math.min(stationCap, orderArrivalRate);
        double throughput = Math.min(agvCap, plateau);
        double utilization = Math.min(100, throughput / agvCap * 100);

        // 步骤 1：AGV 系统配置
        ctx.step(String.format("AGV 货到人拣选：%d 台 AGV（%.1f m/s）、%d 个拣选站、货架命中率 %.0f%%、"
                        + "电池续航 %.1f h、冲突策略 %s、订单到达 %.0f 单/h",
                agvCount, agvSpeed, workstationCount, hitRate * 100, batteryHours,
                conflictStrategy, orderArrivalRate),
                Map.of("agv_count", agvCount, "workstation_count", workstationCount));

        // 步骤 2：单台任务循环与产能测算
        ctx.step(String.format("单台循环 %.0f s/趟（往返 %.0f m + 交接 %.0f s）→ %.1f 趟/h → 有效产能 %.1f 单/h/台"
                        + "（命中率 %.0f%% × 冲突系数 %.2f × 可用性 %.2f）；AGV 总产能 %.0f 单/h",
                cycleSec, AVG_ROUND_TRIP_M, HANDOVER_S, tripsPerHour, capPerAgv,
                hitRate * 100, conflictFactor, batteryFactor, agvCap),
                Map.of("cycle_sec", round2(cycleSec), "cap_per_agv", round2(capPerAgv)));

        // 步骤 3：吞吐量与瓶颈漂移（AGV 敏感性）
        List<Double> agvX = new ArrayList<>();
        List<Double> thrY = new ArrayList<>();
        List<Double> waitY = new ArrayList<>();
        List<Double> confY = new ArrayList<>();
        double conflictBase = conflictStrategy.equals("wait") ? 1.0
                : conflictStrategy.equals("detour") ? 0.85 : 0.60;
        int optimal = 200;
        for (int n = 5; n <= 200; n += 5) {
            double capN = n * capPerAgv;
            double thrN = Math.min(capN, plateau);
            agvX.add((double) n);
            thrY.add(round2(thrN));
            double wait = thrN < orderArrivalRate ? (orderArrivalRate - thrN) / thrN * 60.0 : 0;
            waitY.add(round2(wait));
            double conflicts = n * (n - 1) / 2.0 * (orderArrivalRate / 5000.0) * conflictBase / 60.0;
            confY.add(round2(conflicts));
            if (thrN >= plateau && optimal == 200) {
                optimal = n;
            }
        }
        String bottleneck = agvCap <= stationCap && agvCap <= orderArrivalRate
                ? "RCS-AGV搬运" : stationCap <= orderArrivalRate ? "拣选工作站" : "订单到达率";
        ctx.step(String.format("当前吞吐 %.0f 单/h（瓶颈：%s）；AGV 从 5 台到 200 台：吞吐 %.0f → %.0f 单/h，"
                        + "先线性增长 → 边际递减 → 在 %.0f 台后触及上限（瓶颈漂移）",
                throughput, bottleneck, thrY.get(0), thrY.get(thrY.size() - 1), (double) optimal),
                Map.of("throughput", round2(throughput), "optimal_agv_count", optimal));

        // 步骤 4：最优 AGV 数量与路径冲突分析
        ctx.step(String.format("最优 AGV 数量 %d 台（达到拣选站/订单上限的最少台数）；"
                        + "路径冲突随台数平方增长（%s 策略系数 %.2f），AGV 利用率 %.1f%%",
                optimal, conflictStrategy, conflictBase, utilization),
                Map.of("agv_utilization", round2(utilization), "conflict_strategy", conflictStrategy));

        Map<String, Object> waitSeries = new LinkedHashMap<>();
        waitSeries.put("x", agvX);
        waitSeries.put("series", List.of(Map.of("name", "订单平均等待(分钟)", "data", waitY)));
        Map<String, Object> conflictSeries = new LinkedHashMap<>();
        conflictSeries.put("x", agvX);
        conflictSeries.put("series", List.of(Map.of("name", "路径冲突(次/h)", "data", confY)));

        // 输出指标（FR-007）
        ctx.output("throughput", "吞吐量", "scalar", round2(throughput), "单/h");
        ctx.output("agv_utilization", "AGV平均利用率", "gauge", round2(utilization), "%");
        ctx.output("wait_time", "订单平均等待时间", "series", waitSeries, "分钟");
        ctx.output("optimal_agv_count", "最优AGV数量", "scalar", (double) optimal, "台");
        ctx.output("conflict_count", "路径冲突次数", "series", conflictSeries, "次");
    }
}
