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
 * 智慧物流多系统协同仿真执行器（T053，CH2-009，RCS/iWMS/FIS/PLP）。
 * 模型：订单经 iWMS 库位分配（策略效率系数）→ RCS 调度 AGV 搬运（台数×单台产能）→
 * FIS 拣选（模式产能）→ PLP 全程追踪；系统总吞吐 = 三子系统产能最小值（短板效应）
 * × 通信延迟损耗；逐步增加 AGV 数量观察吞吐提升与瓶颈转移。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class MultiSystemExecutor implements ScenarioExecutor {

    private static final Set<String> STORAGE_STRATEGIES = Set.of("random", "category", "abc", "nearest");
    private static final Set<String> PICKING_MODES = Set.of("man_to_goods", "goods_to_man", "mixed");
    private static final Set<String> WAVE_STRATEGIES = Set.of("timed", "continuous", "mixed");
    private static final double INFLOW = 1000;   // 订单流入（单/时）

    @Override
    public String engineKey() {
        return "multi-system";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "agv_count", 5, 200, errors);
        doubleParam(params, "agv_speed", 0.5, 2.0, errors);
        enumParam(params, "storage_strategy", STORAGE_STRATEGIES, errors);
        enumParam(params, "picking_mode", PICKING_MODES, errors);
        doubleParam(params, "plp_precision", 0.1, 1.0, errors);
        Double commDelay = doubleParam(params, "comm_delay_ms", 50, 500, errors);
        enumParam(params, "wave_strategy", WAVE_STRATEGIES, errors);
        // 约束 comm_delay_ok：系统通信延迟需小于 1 秒
        if (errors.isEmpty() && commDelay != null && commDelay >= 1000) {
            errors.add("comm_delay_ok 约束不满足：comm_delay_ms 必须小于 1000");
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
        double agvSpeed = ((Number) params.get("agv_speed")).doubleValue();
        String storageStrategy = String.valueOf(params.get("storage_strategy"));
        String pickingMode = String.valueOf(params.get("picking_mode"));
        double plpPrecision = ((Number) params.get("plp_precision")).doubleValue();
        double commDelay = ((Number) params.get("comm_delay_ms")).doubleValue();
        String waveStrategy = String.valueOf(params.get("wave_strategy"));

        // 子系统产能模型
        double iwmsFactor = switch (storageStrategy) {
            case "random" -> 0.70;
            case "category" -> 0.82;
            case "nearest" -> 0.86;
            default -> 0.90;   // abc
        };
        double fisRate = switch (pickingMode) {
            case "man_to_goods" -> 120.0;
            case "mixed" -> 150.0;
            default -> 180.0;  // goods_to_man
        };
        double waveFactor = switch (waveStrategy) {
            case "timed" -> 0.85;
            case "continuous" -> 1.0;
            default -> 0.95;   // mixed
        };
        double iwmsCap = 2500 * iwmsFactor;                     // 库位分配产能（单/时）
        double agvCap = agvCount * 3600.0 / (100 / agvSpeed + 30);  // 搬运产能（单/时）
        double fisCap = fisRate * 8;                            // 拣选产能（8 个拣选站）
        double plpCap = 3000 * (1 - plpPrecision * 0.3);        // 追踪产能（精度越高开销越大）
        double delayLoss = 1 - Math.min(0.5, commDelay / 1000.0 * 0.3);
        double throughput = min4(iwmsCap, agvCap, fisCap, plpCap) * delayLoss * waveFactor;

        // 步骤 1：四系统配置
        ctx.step(String.format("多系统配置：iWMS 库位策略 %s、RCS AGV %d 台（%.1f m/s）、FIS 拣选 %s、"
                        + "PLP 精度 %.1f m、通信延迟 %.0f ms、波次 %s",
                storageStrategy, agvCount, agvSpeed, pickingMode, plpPrecision, commDelay, waveStrategy),
                Map.of("agv_count", agvCount, "comm_delay_ms", round2(commDelay)));

        // 步骤 2：子系统产能测算（短板效应）
        double minCap = min4(iwmsCap, agvCap, fisCap, plpCap);
        String bottleneck;
        if (minCap == agvCap) {
            bottleneck = "RCS-AGV搬运";
        } else if (minCap == fisCap) {
            bottleneck = "FIS拣选";
        } else if (minCap == plpCap) {
            bottleneck = "PLP包裹定位";
        } else {
            bottleneck = "iWMS库位分配";
        }
        ctx.step(String.format("子系统产能：iWMS %.0f、RCS-AGV %.0f、FIS %.0f、PLP %.0f 单/时 → 短板 %s（%.0f 单/时）",
                iwmsCap, agvCap, fisCap, plpCap, bottleneck, minCap),
                Map.of("iwms_cap", round2(iwmsCap), "agv_cap", round2(agvCap), "fis_cap", round2(fisCap), "plp_cap", round2(plpCap)));

        // 步骤 3：吞吐量与通信延迟影响
        double throughputNoDelay = min4(iwmsCap, agvCap, fisCap, plpCap);
        double throughputBase = throughputNoDelay * (1 - Math.min(0.5, 50.0 / 1000.0 * 0.3));
        double utilization = Math.min(1, INFLOW / agvCap) * 100;
        ctx.step(String.format("系统总吞吐 %.0f 单/时（延迟损耗 %.1f%%）；AGV 利用率 %.1f%%（订单流入 %.0f 单/时）",
                throughput, (1 - delayLoss) * 100, utilization, INFLOW),
                Map.of("throughput", round2(throughput), "agv_utilization", round2(utilization)));

        // 步骤 4：AGV 数量敏感性（瓶颈转移）与延迟影响对比
        List<Double> x = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        for (int n = 5; n <= 200; n += 5) {
            double capN = n * 3600.0 / (100 / agvSpeed + 30);
            x.add((double) n);
            y.add(round2(min4(iwmsCap, capN, fisCap, plpCap) * delayLoss));
        }
        Map<String, Object> orderTime = new LinkedHashMap<>();
        orderTime.put("x", x);
        orderTime.put("series", List.of(Map.of("name", "总吞吐(单/时)", "data", y)));
        List<Map<String, Object>> delayImpact = new ArrayList<>();
        delayImpact.add(Map.of("name", "当前延迟(" + round2(commDelay) + "ms)", "value", round2(throughput)));
        delayImpact.add(Map.of("name", "低延迟基准(50ms)", "value", round2(throughputBase)));
        ctx.step(String.format("AGV 敏感性：5 台 %.0f → 200 台 %.0f 单/时；瓶颈随 AGV 增加转移（%s → iWMS/FIS）",
                y.get(0), y.get(y.size() - 1), bottleneck), Map.of("sensitivity_size", x.size()));

        Map<String, Object> topo = new LinkedHashMap<>();
        topo.put("nodes", List.of(
                Map.of("id", "iwms", "name", "iWMS库位分配", "type", "system"),
                Map.of("id", "rcs", "name", "RCS-AGV搬运", "type", "system"),
                Map.of("id", "fis", "name", "FIS拣选", "type", "system"),
                Map.of("id", "plp", "name", "PLP包裹定位", "type", "system"),
                Map.of("id", "bn", "name", "瓶颈：" + bottleneck, "type", "bottleneck")));
        topo.put("edges", List.of(
                Map.of("source", "iwms", "target", "rcs"),
                Map.of("source", "rcs", "target", "fis"),
                Map.of("source", "fis", "target", "plp"),
                Map.of("source", "iwms", "target", "bn"),
                Map.of("source", "rcs", "target", "bn"),
                Map.of("source", "fis", "target", "bn")));

        // 输出指标（FR-007）
        ctx.output("throughput", "系统总吞吐量", "scalar", round2(throughput), "单/时");
        ctx.output("order_time", "单订单处理时间", "series", orderTime, "单/时");
        ctx.output("agv_utilization", "AGV利用率", "gauge", round2(utilization), "%");
        ctx.output("bottleneck", "系统瓶颈识别", "topo", topo, null);
        ctx.output("delay_impact", "通信延迟影响", "compare", delayImpact, "单/时");
    }

    private double min4(double a, double b, double c, double d) {
        return Math.min(a, Math.min(b, Math.min(c, d)));
    }
}
