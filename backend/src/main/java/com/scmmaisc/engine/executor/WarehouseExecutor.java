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
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 仓储全流程作业仿真执行器（T028，CH3-004）：入库验收上架 → 保管盘点 → 出库拣选发货。
 * 模型：入库处理时长随抽检比例上升；拣选效率随拣选模式（摘果/播种/混合）与库位策略
 * （随机/固定/ABC）变化；库存准确率随盘点周期衰减（账实不符累积）。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class WarehouseExecutor implements ScenarioExecutor {

    private static final Set<String> PICKING_MODES = Set.of("order", "batch", "mixed");
    private static final Set<String> SLOTTING_STRATEGIES = Set.of("random", "fixed", "abc");

    @Override
    public String engineKey() {
        return "warehouse";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer inbound = intParam(params, "daily_inbound", 100, 10_000, errors);
        Integer outbound = intParam(params, "daily_outbound", 100, 10_000, errors);
        Integer skus = intParam(params, "sku_count", 100, 50_000, errors);
        String picking = enumParam(params, "picking_mode", PICKING_MODES, errors);
        String slotting = enumParam(params, "slotting_strategy", SLOTTING_STRATEGIES, errors);
        Integer cyclePeriod = intParam(params, "cycle_count_period", 1, 30, errors);
        Double inspection = doubleParam(params, "inspection_rate", 0.01, 0.30, errors);
        if (errors.isEmpty() && inbound != null && outbound != null && skus != null
                && picking != null && slotting != null && cyclePeriod != null && inspection != null) {
            // 约束 flow_balance：日入库与日出库差异过大（库存持续失衡）
            int diff = Math.abs(inbound - outbound);
            if (diff > Math.max(outbound * 0.3, 100)) {
                errors.add(String.format("flow_balance 约束不满足：日入库 %d 与日出库 %d 差异 %d 超过 30%%",
                        inbound, outbound, diff));
            }
            // 约束 accuracy_99：库存准确率须 > 99%（随盘点周期衰减）
            double accuracy = 1 - cyclePeriod * 0.0004;
            if (accuracy < 0.99) {
                errors.add(String.format("accuracy_99 约束不满足：盘点周期 %d 天时库存准确率降至 %.2f%%（需 ≤ %d 天）",
                        cyclePeriod, accuracy * 100, 25));
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
        int inbound = ((Number) params.get("daily_inbound")).intValue();
        int outbound = ((Number) params.get("daily_outbound")).intValue();
        int skus = ((Number) params.get("sku_count")).intValue();
        String picking = String.valueOf(params.get("picking_mode"));
        String slotting = String.valueOf(params.get("slotting_strategy"));
        int cyclePeriod = ((Number) params.get("cycle_count_period")).intValue();
        double inspection = ((Number) params.get("inspection_rate")).doubleValue();

        // 步骤 1：入库验收上架
        double inboundMinutes = inbound * (1.2 + inspection * 2.0);   // 抽检比例越高入库越慢
        ctx.step(String.format("入库：日入库 %d 件，抽检率 %.0f%% → 当日入库处理约 %.0f 分钟（卸货+质检+上架）",
                inbound, inspection * 100, inboundMinutes),
                Map.of("inbound_processing_minutes", round2(inboundMinutes)));

        // 步骤 2：保管与循环盘点
        double accuracy = 1 - cyclePeriod * 0.0004;   // 盘点周期越长账实误差累积越多
        ctx.step(String.format("保管：每 %d 天循环盘点一次，期间库存准确率衰减至 %.2f%%（FIFO 效期管理）",
                cyclePeriod, accuracy * 100), Map.of("inventory_accuracy", round2(accuracy * 100)));

        // 步骤 3：出库拣选发货（模式 × 库位策略）
        double pickingFactor = switch (picking) {
            case "order" -> 1.0;   // 摘果式：按订单逐个拣
            case "batch" -> 1.35;  // 播种式：按商品集中拣再分播
            default -> 1.2;        // 混合
        };
        double slottingFactor = switch (slotting) {
            case "random" -> 0.9;  // 随机库位：拣选路径长
            case "fixed" -> 1.05;  // 固定库位
            default -> 1.2;        // ABC 分类：A 类近出口
        };
        double efficiency = 120 * pickingFactor * slottingFactor;   // 行/人时
        double errorRate = 0.005 / pickingFactor * (1 + (1 - accuracy) * 5);
        ctx.step(String.format("拣选：%s × %s → 拣选效率 %.0f 行/人时，出库差错率 %.3f%%",
                picking, slotting, efficiency, errorRate * 100),
                Map.of("picking_efficiency", round2(efficiency), "outbound_error_rate", round2(errorRate * 100)));

        // 步骤 4：30 日入库处理时长曲线（随抽检比例波动）
        List<Integer> days = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        for (int d = 1; d <= 30; d++) {
            days.add(d);
            double t = inbound * (1.2 + inspection * 2.0 * (1 + 0.05 * (d % 5)));
            times.add(round2(t));
        }
        Map<String, Object> timeSeries = series(days, "入库处理时长(分钟)", times);
        ctx.step("入库处理时长曲线（30 日）：抽检与批量波动直接影响入库节拍", timeSeries);

        // 步骤 5：绩效汇总
        double capacity = (inbound + outbound) / (double) skus * 100;   // 库容利用率（SKU 数代理容量）
        capacity = Math.min(capacity, 100);
        ctx.step(String.format("汇总：拣选效率 %.0f 行/人时、库存准确率 %.2f%%、库容利用率 %.0f%%",
                efficiency, accuracy * 100, capacity),
                Map.of("picking_efficiency", round2(efficiency), "inventory_accuracy", round2(accuracy * 100),
                        "capacity_utilization", round2(capacity)));

        // 输出指标（FR-007）
        ctx.output("inbound_processing_time", "入库处理时长", "series", timeSeries, "分钟");
        ctx.output("picking_efficiency", "拣选效率", "scalar", round2(efficiency), "行/人时");
        ctx.output("inventory_accuracy", "库存准确率", "gauge", round2(accuracy * 100), "%");
        ctx.output("outbound_error_rate", "出库差错率", "scalar", round2(errorRate * 100), "%");
        ctx.output("capacity_utilization", "库容利用率", "gauge", round2(capacity), "%");
    }
}
