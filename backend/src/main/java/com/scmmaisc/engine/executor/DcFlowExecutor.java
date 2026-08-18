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
 * 配送中心集货-分拣-配货-配送全流程仿真执行器（T055，CH4-003，角色扮演）。
 * 模型：验收→入库→波次释放→摘果式（按订单逐 SKU）vs 播种式（集中拣选+分播）→
 * 复核→包装→按线路集货→装车；拣选/复核/包装/分拣 四环节工时利用率对比，多品少量
 * 推荐播种式、单品多量推荐摘果式。确定性模型：seed 无关（FR-008）。
 */
@Component
public class DcFlowExecutor implements ScenarioExecutor {

    private static final Set<String> PICKING_MODES = Set.of("order_pick", "batch_pick", "mixed");
    private static final double[] BASE_EFF = {120, 300, 200};   // 摘果/播种/混合 行/人时
    private static final double WORK_HOURS = 16.0;              // 两班作业时长（h/日）
    private static final double COUNT_RATE = 80.0;              // 复核（单/人时）
    private static final double PACK_RATE = 60.0;               // 包装（单/人时）

    @Override
    public String engineKey() {
        return "dc-flow";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "daily_orders", 500, 50000, errors);
        doubleParam(params, "avg_lines", 1.5, 15, errors);
        enumParam(params, "picking_mode", PICKING_MODES, errors);
        Integer wave = intParam(params, "wave_interval", 15, 120, errors);
        doubleParam(params, "conveyor_speed", 0.5, 3.0, errors);
        intParam(params, "pickers", 5, 100, errors);
        intParam(params, "counters", 5, 100, errors);
        intParam(params, "packers", 5, 100, errors);
        intParam(params, "grid_count", 20, 200, errors);
        // 约束 same_day_ok：波次间隔 ≤ 60 分钟保证当日订单当日发出
        if (errors.isEmpty() && wave != null && wave > 60) {
            errors.add("same_day_ok 约束不满足：wave_interval (" + wave + ") 必须 ≤ 60 分钟");
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
        double avgLines = ((Number) params.get("avg_lines")).doubleValue();
        String pickingMode = String.valueOf(params.get("picking_mode"));
        int waveInterval = ((Number) params.get("wave_interval")).intValue();
        double conveyorSpeed = ((Number) params.get("conveyor_speed")).doubleValue();
        int pickers = ((Number) params.get("pickers")).intValue();
        int counters = ((Number) params.get("counters")).intValue();
        int packers = ((Number) params.get("packers")).intValue();
        int gridCount = ((Number) params.get("grid_count")).intValue();

        double linesPerDay = dailyOrders * avgLines;
        int modeIdx = pickingMode.equals("order_pick") ? 0 : pickingMode.equals("batch_pick") ? 1 : 2;
        double effAdj = BASE_EFF[modeIdx] * (0.9 + conveyorSpeed * 0.2);
        double pickHours = linesPerDay / (pickers * effAdj);
        double counterHours = dailyOrders / (counters * COUNT_RATE);
        double packHours = dailyOrders / (packers * PACK_RATE);
        double sortHours = dailyOrders / (conveyorSpeed * 4000);
        double wavesPerDay = 24.0 * 60 / waveInterval;

        // 步骤 1：DC 作业流程与配置
        ctx.step(String.format("配送中心全流程：验收→入库→波次释放（%.0f 波/日，%.0f 单/波）→拣选→复核→包装"
                        + "→集货→装车；日订单 %d、平均 %.1f 行/单、拣选 %d 人、复核 %d 人、包装 %d 人、"
                        + "传送带 %.1f m/s、播种墙 %d 格",
                wavesPerDay, dailyOrders / Math.max(1, wavesPerDay), dailyOrders, avgLines,
                pickers, counters, packers, conveyorSpeed, gridCount),
                Map.of("daily_orders", dailyOrders, "waves_per_day", round2(wavesPerDay)));

        // 步骤 2：拣选模式效率对比
        ctx.step(String.format("拣选模式 %s：人均效率 %.0f 行/人时（摘果 120/播种 300/混合 200，"
                        + "传送带加速系数 %.2f）；拣选总工时 %.0f 小时",
                pickingMode, effAdj, 0.9 + conveyorSpeed * 0.2, pickHours),
                Map.of("pick_efficiency", round2(effAdj), "pick_hours", round2(pickHours)));

        // 步骤 3：各环节工时利用率与瓶颈
        double[] utils = {pickHours / WORK_HOURS * 100, counterHours / WORK_HOURS * 100,
                packHours / WORK_HOURS * 100, sortHours / WORK_HOURS * 100};
        String[] stageNames = {"拣选", "复核", "包装", "分拣"};
        List<Map<String, Object>> utilItems = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            utilItems.add(Map.of("name", stageNames[i], "value", round2(utils[i])));
        }
        int bn = 0;
        for (int i = 1; i < 4; i++) {
            if (utils[i] > utils[bn]) {
                bn = i;
            }
        }
        ctx.step(String.format("工时利用率：拣选 %.0f%%、复核 %.0f%%、包装 %.0f%%、分拣 %.0f%% → 瓶颈：%s",
                utils[0], utils[1], utils[2], utils[3], stageNames[bn]),
                Map.of("bottleneck", stageNames[bn]));

        // 步骤 4：订单处理周期与拣选模式决策
        List<Double> hx = new ArrayList<>();
        List<Double> hy = new ArrayList<>();
        for (int bin = 1; bin <= 12; bin++) {
            double pressure = 1 + (bin % 3 == 0 ? 0.3 : 0.0) + (bin == 12 ? 0.2 : 0.0);
            double cycleH = waveInterval / 60.0 + 1.0 + Math.max(0, utils[bn] - 80) / 100.0 * 4.0 * pressure;
            hx.add((double) bin);
            hy.add(round2(cycleH));
        }
        Map<String, Object> cycleSeries = new LinkedHashMap<>();
        cycleSeries.put("x", hx);
        cycleSeries.put("series", List.of(Map.of("name", "订单处理周期(小时)", "data", hy)));
        String bestMode = avgLines >= 3
                ? "播种式(batch_pick)：多品少量订单集中拣选+分播，减少重复走动"
                : "摘果式(order_pick)：单品多量订单按单拣取，避免分播环节";
        ctx.step(String.format("模式决策：平均 %.1f 行/单 → %s（当前配置 %s）",
                avgLines, bestMode, pickingMode),
                Map.of("best_mode", bestMode));

        Map<String, Object> topo = new LinkedHashMap<>();
        topo.put("nodes", List.of(
                Map.of("id", "pick", "name", "拣选", "type", "stage"),
                Map.of("id", "count", "name", "复核", "type", "stage"),
                Map.of("id", "pack", "name", "包装", "type", "stage"),
                Map.of("id", "sort", "name", "分拣集货", "type", "stage"),
                Map.of("id", "bn", "name", "瓶颈：" + stageNames[bn], "type", "bottleneck")));
        topo.put("edges", List.of(
                Map.of("source", "pick", "target", "count"),
                Map.of("source", "count", "target", "pack"),
                Map.of("source", "pack", "target", "sort"),
                Map.of("source", "pick", "target", "bn"),
                Map.of("source", "count", "target", "bn"),
                Map.of("source", "pack", "target", "bn"),
                Map.of("source", "sort", "target", "bn")));

        // 输出指标（FR-007）
        ctx.output("order_cycle", "订单处理周期(下单→出库)", "series", cycleSeries, "小时");
        ctx.output("pick_efficiency", "人均拣选效率", "scalar", round2(effAdj), "行/人时");
        ctx.output("station_utilization", "各环节工时利用率", "compare", utilItems, "%");
        ctx.output("best_mode", "最优拣选模式选择", "scalar", bestMode, null);
        ctx.output("bottleneck", "瓶颈环节识别", "topo", topo, null);
    }
}
