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
 * GPS/BDS/GIS/LBS 车辆定位与路径规划仿真执行器（T054，CH3-002）。
 * 模型：定位精度与上报频率决定轨迹质量 → 轨迹偏离率与电子围栏报警；实时路况
 * 更新频率驱动动态路径重规划 → 节省里程；车队位置按城区×时段分布生成监控热图。
 * 随机性仅用于热图与报警分布，ctx.random() 种子可复现（FR-008）。
 */
@Component
public class GpsLocationExecutor implements ScenarioExecutor {

    private static final List<String> DISTRICTS = List.of("A区", "B区", "C区", "D区", "E区", "F区", "G区", "H区");
    private static final List<String> SLOTS = List.of("早高峰", "上午", "午间", "下午", "晚高峰", "夜间");
    private static final double[] SLOT_WEIGHTS = {0.15, 0.10, 0.12, 0.15, 0.28, 0.20};

    @Override
    public String engineKey() {
        return "gps-location";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double accuracy = doubleParam(params, "positioning_accuracy", 1, 30, errors);
        Double interval = doubleParam(params, "report_interval", 1, 60, errors);
        intParam(params, "vehicle_count", 10, 500, errors);
        intParam(params, "delivery_points", 20, 200, errors);
        doubleParam(params, "traffic_update_interval", 30, 300, errors);
        doubleParam(params, "fence_radius", 100, 2000, errors);
        // 约束 accuracy_ok / delay_ok
        if (errors.isEmpty() && accuracy != null && accuracy >= 15) {
            errors.add("accuracy_ok 约束不满足：positioning_accuracy (" + accuracy + ") 必须 < 15 m（市内定位精度）");
        }
        if (errors.isEmpty() && interval != null && interval > 5) {
            errors.add("delay_ok 约束不满足：report_interval (" + interval + ") 必须 ≤ 5 秒（位置更新延迟）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double positioningAccuracy = ((Number) params.get("positioning_accuracy")).doubleValue();
        double reportInterval = ((Number) params.get("report_interval")).doubleValue();
        int vehicleCount = ((Number) params.get("vehicle_count")).intValue();
        int deliveryPoints = ((Number) params.get("delivery_points")).intValue();
        double trafficUpdateInterval = ((Number) params.get("traffic_update_interval")).doubleValue();
        double fenceRadius = ((Number) params.get("fence_radius")).doubleValue();

        // 步骤 1：车队与定位配置
        ctx.step(String.format("监控车队 %d 辆、配送点 %d 个：定位精度 %.0f m、上报间隔 %.0f s、"
                        + "路况刷新 %.0f s、电子围栏半径 %.0f m",
                vehicleCount, deliveryPoints, positioningAccuracy, reportInterval,
                trafficUpdateInterval, fenceRadius),
                Map.of("vehicle_count", vehicleCount, "delivery_points", deliveryPoints));

        // 步骤 2：轨迹偏离率（精度/上报频率/路况滞后共同作用）
        double deviation = Math.min(20, Math.max(0.5,
                positioningAccuracy * 0.2 + reportInterval * 0.05 + (trafficUpdateInterval - 30) / 270.0 * 0.5));
        ctx.step(String.format("轨迹偏离率 %.2f%%：定位精度 %.0f m 贡献 %.1f 个百分点，"
                        + "上报间隔 %.0f s 贡献 %.2f 个百分点，路况滞后贡献 %.2f 个百分点",
                deviation, positioningAccuracy, positioningAccuracy * 0.2,
                reportInterval, reportInterval * 0.05, (trafficUpdateInterval - 30) / 270.0 * 0.5),
                Map.of("deviation_rate", round2(deviation)));

        // 步骤 3：动态路径重规划节省里程
        double baseMileage = deliveryPoints * 1.2;
        double savingsPct = 8 + (300 - trafficUpdateInterval) / 300.0 * 8 + (reportInterval <= 5 ? 2 : 0);
        double savedMileage = baseMileage * savingsPct / 100.0;
        ctx.step(String.format("路径优化节省里程 %.1f km/日（基准里程 %.0f km，节省率 %.1f%%："
                        + "路况刷新越快越省，秒级上报再 +2 个百分点）",
                savedMileage, baseMileage, savingsPct),
                Map.of("saved_mileage", round2(savedMileage), "savings_pct", round2(savingsPct)));

        // 步骤 4：电子围栏预警与位置热图
        List<Double> hx = new ArrayList<>();
        List<Double> hy = new ArrayList<>();
        for (int h = 1; h <= 12; h++) {
            double surge = 1 + 0.5 * Math.exp(-Math.pow(h - 8, 2) / 8) + 0.5 * Math.exp(-Math.pow(h - 18, 2) / 8);
            double alerts = vehicleCount * deviation / 100.0 * surge * (0.7 + 0.6 * ctx.random().nextDouble());
            hx.add((double) h);
            hy.add(round2(alerts));
        }
        Map<String, Object> fenceSeries = new LinkedHashMap<>();
        fenceSeries.put("x", hx);
        fenceSeries.put("series", List.of(Map.of("name", "围栏报警(次/h)", "data", hy)));
        int[][] counts = new int[DISTRICTS.size()][SLOTS.size()];
        for (int v = 0; v < vehicleCount; v++) {
            int row = ctx.random().nextInt(DISTRICTS.size());
            double r = ctx.random().nextDouble();
            int col = 0;
            double acc = 0;
            for (int c = 0; c < SLOTS.size(); c++) {
                acc += SLOT_WEIGHTS[c];
                if (r <= acc) {
                    col = c;
                    break;
                }
            }
            counts[row][col]++;
        }
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", DISTRICTS);
        heatmap.put("columns", SLOTS);
        List<List<Double>> data = new ArrayList<>();
        for (int[] row : counts) {
            List<Double> rr = new ArrayList<>();
            for (int c : row) {
                rr.add((double) c);
            }
            data.add(rr);
        }
        heatmap.put("data", data);
        ctx.step(String.format("电子围栏预警集中早晚高峰（偏离/超速/异常停留）；"
                        + "位置热图显示车辆在城区 × 时段的分布密度",
                hy.get(7), hy.get(11)),
                Map.of("peak_alerts", round2(hy.get(7)), "night_alerts", round2(hy.get(11))));

        // 输出指标（FR-007）
        ctx.output("deviation_rate", "轨迹偏离率", "scalar", round2(deviation), "%");
        ctx.output("saved_mileage", "路径优化节省里程", "scalar", round2(savedMileage), "km");
        ctx.output("position_heatmap", "实时位置地图", "heatmap", heatmap, null);
        ctx.output("fence_alerts", "围栏报警次数", "series", fenceSeries, "次");
    }
}
