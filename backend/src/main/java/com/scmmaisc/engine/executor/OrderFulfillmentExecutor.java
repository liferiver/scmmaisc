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
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 电商订单履约全流程优化仿真执行器（T055，CH4-007）。
 * 模型：下单→审核→库存分配（就近/就全/就廉）→拆单决策→拣货包装→出库→揽收中转派送→
 * 签收；截单时间决定当日发出比例 → 履约周期分布（dist）、准时履约率、拆单率/包裹数、
 * 各截单时点对比。确定性模型：seed 无关（FR-008）。
 */
@Component
public class OrderFulfillmentExecutor implements ScenarioExecutor {

    private static final Set<String> CUTOFFS = Set.of("15:00", "17:00", "20:00", "23:00");
    private static final Set<String> ALLOCATION_STRATEGIES = Set.of("nearest", "full", "cheap");
    private static final Set<String> SPLIT_STRATEGIES = Set.of("none", "by_warehouse", "by_category");

    @Override
    public String engineKey() {
        return "order-fulfillment";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "cutoff_time", CUTOFFS, errors);
        enumParam(params, "allocation_strategy", ALLOCATION_STRATEGIES, errors);
        enumParam(params, "split_strategy", SPLIT_STRATEGIES, errors);
        doubleParam(params, "sla_target", 0.95, 0.999, errors);
        intParam(params, "cancel_window", 0, 24, errors);
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String cutoff = String.valueOf(params.get("cutoff_time"));
        String allocation = String.valueOf(params.get("allocation_strategy"));
        String split = String.valueOf(params.get("split_strategy"));
        double slaTarget = ((Number) params.get("sla_target")).doubleValue();
        int cancelWindow = ((Number) params.get("cancel_window")).intValue();

        // 截单时间 → 当日发出比例
        double sameDayShare = switch (cutoff) {
            case "15:00" -> 0.70;
            case "17:00" -> 0.85;
            case "20:00" -> 0.95;
            default -> 0.98;   // 23:00
        };
        double allocationPenalty = switch (allocation) {
            case "full" -> 0.5;    // 就全：跨仓调拨耗时
            case "cheap" -> 1.0;   // 就廉：远仓发货
            default -> 0.0;        // nearest
        };
        double splitPenalty = split.equals("by_warehouse") ? 0.5 : split.equals("by_category") ? 1.0 : 0.0;

        // 步骤 1：订单履约全链路配置
        ctx.step(String.format("履约链路：下单→审核→库存分配（%s）→拆单（%s）→拣货包装→出库→"
                        + "揽收→中转→派送→签收；截单 %s、SLA 目标 %.1f%%、退货拦截窗 %d h",
                allocation, split, cutoff, slaTarget * 100, cancelWindow),
                Map.of("cutoff_time", cutoff, "sla_target", slaTarget));

        // 步骤 2：截单时间与当日发出比例
        double avgCycle = 27.0 * sameDayShare + 51.0 * (1 - sameDayShare) + allocationPenalty + splitPenalty;
        ctx.step(String.format("截单 %s：当日发出比例 %.0f%%（截单越晚当日单越多，后端作业压力越大）"
                        + "；平均履约周期 %.1f 小时",
                cutoff, sameDayShare * 100, avgCycle),
                Map.of("same_day_share", round2(sameDayShare * 100)));

        // 步骤 3：履约周期分布与拆单分析
        double p24 = 8.0;
        double p48 = sameDayShare * 85 + (1 - sameDayShare) * 30;
        double p72 = 100 - p24 - p48;
        List<Map<String, Object>> cycleDist = new ArrayList<>();
        cycleDist.add(Map.of("name", "<24小时", "value", round2(p24)));
        cycleDist.add(Map.of("name", "24-48小时", "value", round2(p48)));
        cycleDist.add(Map.of("name", ">48小时", "value", round2(p72)));
        double splitRate = switch (split) {
            case "by_warehouse" -> 20.0;
            case "by_category" -> 35.0;
            default -> 0.0;
        };
        double packagesPerOrder = 1 + splitRate / 100.0 * 0.8;
        ctx.step(String.format("履约周期分布：<24h %.0f%%、24-48h %.0f%%、>48h %.0f%%；拆单率 %.0f%%"
                        + " → 每单平均包裹 %.2f 件（拆单提升履约率但增加包裹与成本）",
                p24, p48, p72, splitRate, packagesPerOrder),
                Map.of("split_rate", round2(splitRate), "packages_per_order", round2(packagesPerOrder)));

        // 步骤 4：SLA 达成与截单影响对比
        double ontime = 99.2 - (sameDayShare - 0.70) * 6.0
                - (allocation.equals("full") ? 0.3 : allocation.equals("cheap") ? 0.5 : 0.0)
                - (split.equals("by_category") ? 0.3 : 0.0);
        List<Map<String, Object>> cutoffItems = new ArrayList<>();
        double[] shares = {0.70, 0.85, 0.95, 0.98};
        String[] names = {"15:00", "17:00", "20:00", "23:00"};
        for (int i = 0; i < 4; i++) {
            double ot = 99.2 - (shares[i] - 0.70) * 6.0;
            cutoffItems.add(Map.of("name", "截单 " + names[i], "value", round2(ot)));
        }
        boolean slaOk = ontime >= slaTarget * 100;
        ctx.step(String.format("准时履约率 %.2f%%（SLA 目标 %.2f%%：%s）；截单 15:00→23:00 履约率"
                        + "从 99.2%% 降至 %.1f%%，后端作业压力与消费者体验此消彼长",
                ontime, slaTarget * 100, slaOk ? "达成" : "未达成", 99.2 - (0.98 - 0.70) * 6.0),
                Map.of("ontime_rate", round2(ontime), "sla_ok", slaOk));

        // 输出指标（FR-007）
        ctx.output("fulfillment_cycle", "履约周期分布", "dist", cycleDist, "小时");
        ctx.output("ontime_rate", "准时履约率", "gauge", round2(ontime), "%");
        ctx.output("split_rate", "拆单率", "scalar", round2(splitRate), "%");
        ctx.output("packages_per_order", "每单平均包裹数", "scalar", round2(packagesPerOrder), "件");
        ctx.output("cutoff_impact", "截单时间影响", "compare", cutoffItems, "%");
    }
}
