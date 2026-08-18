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
 * 菜鸟平台型物流大数据调度仿真执行器（T055，CH4-005，角色扮演）。
 * 模型：合作快递公司运力池 × 产能弹性 → 大数据路由（成本/时效/均衡策略）→ 电子面单 →
 * 揽收-中转-派送 → 驿站/自提柜末端；输出配送时效曲线、单均成本、调度均衡度、
 * 大促峰值运力缺口与投诉率。确定性模型：seed 无关（FR-008）。
 */
@Component
public class CainiaoExecutor implements ScenarioExecutor {

    private static final Set<String> ROUTING_STRATEGIES = Set.of("cost", "time", "balanced");
    private static final double CAP_PER_CARRIER = 0.3;    // 单家快递公司日常产能（千万单/日）
    private static final double[] BASE_TIME = {52, 30, 40};     // cost/time/balanced 基准时效（小时）
    private static final double[] BASE_COST = {3.0, 4.0, 3.5};  // cost/time/balanced 单均成本（元）

    @Override
    public String engineKey() {
        return "cainiao";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "carrier_count", 5, 20, errors);
        intParam(params, "warehouse_count", 10, 200, errors);
        Double orders = doubleParam(params, "daily_orders_10m", 1, 10, errors);
        enumParam(params, "routing_strategy", ROUTING_STRATEGIES, errors);
        doubleParam(params, "station_density", 0.5, 5, errors);
        Double elasticity = doubleParam(params, "capacity_elasticity", 1.2, 2.0, errors);
        // 约束 peak_capacity_ok：双11 峰值不爆仓
        if (errors.isEmpty() && orders != null && elasticity != null && orders > elasticity * 5) {
            errors.add("peak_capacity_ok 约束不满足：daily_orders_10m (" + orders
                    + ") 必须 ≤ capacity_elasticity × 5 (" + round2(elasticity * 5) + ")");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int carrierCount = ((Number) params.get("carrier_count")).intValue();
        int warehouseCount = ((Number) params.get("warehouse_count")).intValue();
        double dailyOrders = ((Number) params.get("daily_orders_10m")).doubleValue();
        String strategy = String.valueOf(params.get("routing_strategy"));
        double stationDensity = ((Number) params.get("station_density")).doubleValue();
        double elasticity = ((Number) params.get("capacity_elasticity")).doubleValue();

        int stratIdx = strategy.equals("cost") ? 0 : strategy.equals("time") ? 1 : 2;
        double totalCap = carrierCount * CAP_PER_CARRIER * elasticity;
        double baseTime = BASE_TIME[stratIdx] - warehouseCount * 0.05 - (stationDensity - 0.5) * 4.0;
        double unitCost = BASE_COST[stratIdx] * (1.5 / elasticity);
        double balance = switch (stratIdx) {
            case 0 -> 78.0;
            case 1 -> 72.0;
            default -> 96.0;
        } + Math.min(5, (carrierCount - 5) * 0.5);

        // 步骤 1：平台网络与运力池配置
        ctx.step(String.format("菜鸟平台：合作快递 %d 家、合作仓 %d 个、驿站密度 %.1f 个/万人、"
                        + "产能弹性 %.2f× → 运力池 %.2f 千万单/日；路由策略 %s",
                carrierCount, warehouseCount, stationDensity, elasticity, totalCap, strategy),
                Map.of("carrier_count", carrierCount, "total_capacity", round2(totalCap)));

        // 步骤 2：大数据路由与配送时效（14 天促销期）
        List<Double> dx = new ArrayList<>();
        List<Double> dy = new ArrayList<>();
        for (int day = 1; day <= 14; day++) {
            double surge = 1 + 0.4 * Math.exp(-(day - 1) / 2.0);
            double gapShare = Math.max(0, dailyOrders * 2.0 - totalCap) / totalCap;
            dx.add((double) day);
            dy.add(round2(baseTime * surge * (1 + gapShare * 0.8)));
        }
        Map<String, Object> timeSeries = new LinkedHashMap<>();
        timeSeries.put("x", dx);
        timeSeries.put("series", List.of(Map.of("name", "平均配送时效(小时)", "data", dy)));
        ctx.step(String.format("平均配送时效 %.1f 小时（大数据路由 %s 策略：时效基准 %.0f h，"
                        + "仓网/驿站密度优化 -%.0f h）",
                baseTime, strategy, BASE_TIME[stratIdx],
                warehouseCount * 0.05 + (stationDensity - 0.5) * 4.0),
                Map.of("delivery_time", round2(baseTime)));

        // 步骤 3：单均成本与调度均衡度
        ctx.step(String.format("单均成本 %.2f 元/单（%s 策略基准 %.1f 元 × 弹性系数 %.2f）；"
                        + "调度均衡度 %.1f%%（均衡策略最优，成本/时效策略存在运力冷热不均）",
                unitCost, strategy, BASE_COST[stratIdx], 1.5 / elasticity, balance),
                Map.of("unit_cost", round2(unitCost), "dispatch_balance", round2(balance)));

        // 步骤 4：大促峰值运力缺口与末端投诉
        double peakOrders = dailyOrders * 2.0;
        double gap = Math.max(0, peakOrders - totalCap);
        List<Double> complaintX = new ArrayList<>();
        List<Double> complaintY = new ArrayList<>();
        for (int day = 1; day <= 14; day++) {
            double surge = 1.2 * Math.exp(-(day - 1) / 2.5);
            double rate = 0.8 + surge + Math.max(0, 1.5 - stationDensity) * 0.3 + gap / totalCap * 1.5;
            complaintX.add((double) day);
            complaintY.add(round2(rate));
        }
        Map<String, Object> complaintSeries = new LinkedHashMap<>();
        complaintSeries.put("x", complaintX);
        complaintSeries.put("series", List.of(Map.of("name", "投诉率(‰)", "data", complaintY)));
        ctx.step(String.format("双11 峰值 %.2f 千万单/日 vs 运力 %.2f 千万单/日 → 运力缺口 %.0f 万单"
                        + "（产能弹性 %.2f× 可部分缓冲）；投诉率峰值 %.1f‰",
                peakOrders, totalCap, gap * 10000, elasticity, complaintY.get(0)),
                Map.of("peak_gap", round2(gap * 10000)));

        // 输出指标（FR-007）
        ctx.output("delivery_time", "平均配送时效", "series", timeSeries, "小时");
        ctx.output("unit_cost", "单均成本", "scalar", round2(unitCost), "元/单");
        ctx.output("dispatch_balance", "快递公司调度均衡度", "gauge", round2(balance), "%");
        ctx.output("peak_gap", "高峰期运力缺口", "scalar", round2(gap * 10000), "万单");
        ctx.output("complaint_rate", "消费者投诉率", "series", complaintSeries, "‰");
    }
}
