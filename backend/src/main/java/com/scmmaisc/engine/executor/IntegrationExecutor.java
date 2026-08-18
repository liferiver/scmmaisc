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
 * 物流信息系统一体化仿真执行器（T054，CH3-007，角色扮演，进阶）。
 * 模型：OMS（订单接收→拆分→分配）× WMS（波次→拣选→出库）× TMS（调度→在途→签收）
 * 三系统产能测算 → 大促洪峰流量（日均 ×3）下识别瓶颈系统；接口延迟折算每日额外
 * 处理时长，数据不一致率折算系统可用性。确定性模型：seed 无关（FR-008）。
 */
@Component
public class IntegrationExecutor implements ScenarioExecutor {

    private static final Set<String> SPLIT_STRATEGIES = Set.of("by_warehouse", "by_sku", "by_leadtime");
    private static final double OMS_CAP = 4000.0;            // OMS 处理产能（单/h）
    private static final double WMS_CAP_PER_WH = 600.0;      // 单仓作业产能（单/h）
    private static final double TMS_CAP_PER_VEHICLE = 6.0;   // 单车配送产能（单/h）
    private static final double PEAK_FACTOR = 3.0;           // 大促峰值系数
    private static final double API_CALLS_PER_ORDER = 12.0;  // 每单系统间调用次数

    @Override
    public String engineKey() {
        return "integration";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "daily_orders", 500, 50000, errors);
        intParam(params, "warehouse_count", 1, 10, errors);
        enumParam(params, "split_strategy", SPLIT_STRATEGIES, errors);
        doubleParam(params, "wave_interval", 15, 120, errors);
        intParam(params, "vehicle_count", 5, 200, errors);
        doubleParam(params, "api_delay", 0.1, 5, errors);
        Double inconsistency = doubleParam(params, "inconsistency_rate", 0.0001, 0.01, errors);
        // 约束 consistency_ok：三系统数据需最终一致（≤ 1%）
        if (errors.isEmpty() && inconsistency != null && inconsistency > 0.01) {
            errors.add("consistency_ok 约束不满足：inconsistency_rate (" + inconsistency + ") 必须 ≤ 0.01");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int dailyOrders = ((Number) params.get("daily_orders")).intValue();
        int warehouseCount = ((Number) params.get("warehouse_count")).intValue();
        String splitStrategy = String.valueOf(params.get("split_strategy"));
        double waveInterval = ((Number) params.get("wave_interval")).doubleValue();
        int vehicleCount = ((Number) params.get("vehicle_count")).intValue();
        double apiDelay = ((Number) params.get("api_delay")).doubleValue();
        double inconsistencyRate = ((Number) params.get("inconsistency_rate")).doubleValue();

        double avgRate = dailyOrders / 24.0;
        double peakRate = avgRate * PEAK_FACTOR;
        double wmsCap = warehouseCount * WMS_CAP_PER_WH * (30.0 / waveInterval);
        double tmsCap = vehicleCount * TMS_CAP_PER_VEHICLE;

        // 步骤 1：三系统配置与集成架构
        ctx.step(String.format("一体化架构 OMS→WMS→TMS：日订单 %d 单（均速 %.0f 单/h）、%d 个仓库（拆分策略 %s）、"
                        + "波次 %.0f min、%d 辆车、接口延迟 %.1f s、数据不一致率 %.4f",
                dailyOrders, avgRate, warehouseCount, splitStrategy, waveInterval, vehicleCount,
                apiDelay, inconsistencyRate),
                Map.of("daily_orders", dailyOrders, "warehouse_count", warehouseCount));

        // 步骤 2：大促洪峰流量与产能测算
        String bottleneck;
        double bnCap;
        if (OMS_CAP <= wmsCap && OMS_CAP <= tmsCap) {
            bottleneck = "OMS订单处理";
            bnCap = OMS_CAP;
        } else if (wmsCap <= tmsCap) {
            bottleneck = "WMS仓储作业";
            bnCap = wmsCap;
        } else {
            bottleneck = "TMS运力调度";
            bnCap = tmsCap;
        }
        boolean saturated = peakRate > bnCap;
        ctx.step(String.format("产能测算：OMS %.0f、WMS %.0f（%d 仓 × %.0f × 30/%.0fmin 波次）、TMS %.0f 单/h；"
                        + "大促峰值 %.0f 单/h（均速 ×%.0f）→ 瓶颈系统：%s（%s）",
                OMS_CAP, wmsCap, warehouseCount, WMS_CAP_PER_WH, waveInterval, tmsCap, peakRate,
                PEAK_FACTOR, bottleneck, saturated ? "已饱和，订单积压" : "尚有余量"),
                Map.of("peak_rate", round2(peakRate), "bottleneck", bottleneck, "saturated", saturated));

        // 步骤 3：订单-发货全流程耗时与接口延迟影响
        List<Double> hx = new ArrayList<>();
        List<Double> hy = new ArrayList<>();
        for (int h = 1; h <= 24; h++) {
            double surge = 1 + 2.0 * Math.exp(-Math.pow(h - 12, 2) / 18.0);
            double rateH = avgRate * surge;
            double backlog = Math.max(0, (rateH - tmsCap) / tmsCap);
            double flowH = 1.5 + waveInterval / 60.0 + backlog * 6.0;
            hx.add((double) h);
            hy.add(round2(flowH));
        }
        Map<String, Object> flowSeries = new LinkedHashMap<>();
        flowSeries.put("x", hx);
        flowSeries.put("series", List.of(Map.of("name", "订单-发货耗时(小时)", "data", hy)));
        double addedHours = dailyOrders * apiDelay * API_CALLS_PER_ORDER / 3600.0;
        double addedHoursBase = dailyOrders * 0.1 * API_CALLS_PER_ORDER / 3600.0;
        List<Map<String, Object>> delayItems = new ArrayList<>();
        delayItems.add(Map.of("name", "当前延迟(" + round2(apiDelay) + "s)", "value", round2(addedHours)));
        delayItems.add(Map.of("name", "低延迟基准(0.1s)", "value", round2(addedHoursBase)));
        ctx.step(String.format("全流程耗时 24 小时曲线（大促午间峰值可达 %.1f h）；接口延迟折算每日 "
                        + "额外处理 %.0f 小时（12 次调用/单 × %.1f s），降至 0.1 s 可省 %.0f 小时/日",
                hy.get(11), addedHours, apiDelay, addedHours - addedHoursBase),
                Map.of("delay_hours", round2(addedHours)));

        // 步骤 4：数据一致性与系统可用性
        double availability = (1 - inconsistencyRate * 5) * (1 - Math.min(0.05, apiDelay * 0.01)) * 100;
        ctx.step(String.format("系统可用性 %.2f%%（数据不一致率 %.4f 折算 + 接口延迟折算）；"
                        + "建议：拆分策略 %s 在峰值期增大波次频率、补充运力",
                availability, inconsistencyRate, splitStrategy),
                Map.of("availability", round2(availability), "inconsistency_rate", inconsistencyRate));

        Map<String, Object> topo = new LinkedHashMap<>();
        topo.put("nodes", List.of(
                Map.of("id", "oms", "name", "OMS订单管理", "type", "system"),
                Map.of("id", "wms", "name", "WMS仓储管理", "type", "system"),
                Map.of("id", "tms", "name", "TMS运输管理", "type", "system"),
                Map.of("id", "bn", "name", "瓶颈：" + bottleneck, "type", "bottleneck")));
        topo.put("edges", List.of(
                Map.of("source", "oms", "target", "wms"),
                Map.of("source", "wms", "target", "tms"),
                Map.of("source", "oms", "target", "bn"),
                Map.of("source", "wms", "target", "bn"),
                Map.of("source", "tms", "target", "bn")));

        // 输出指标（FR-007）
        ctx.output("flow_time", "订单-发货全流程耗时", "series", flowSeries, "小时");
        ctx.output("bottlenecks", "各系统处理瓶颈", "topo", topo, null);
        ctx.output("delay_impact", "接口延迟对总时效的影响", "compare", delayItems, "小时");
        ctx.output("availability", "系统可用性", "gauge", round2(availability), "%");
    }
}
