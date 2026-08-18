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
 * 供应链"三流联动"仿真执行器（T052，CH1-006）。
 * 模型：订单生命周期按信息流/物流/资金流三条线推进（下单→订单处理→拣货→运输→签收→
 * 结算→回款），信息延迟、物流执行周期、结算周期决定三流耗时；信息准确率低引发返工；
 * 订单-现金周期 = 信息流 + 物流 + 资金流合计，现金流周转天数 = 库存周转 + 结算周期；
 * 耗时最长的流为瓶颈。确定性模型：seed 无关（FR-008）。
 */
@Component
public class ThreeFlowsExecutor implements ScenarioExecutor {

    private static final List<String> STAGES = List.of("下单", "订单处理", "拣货", "运输", "签收", "结算", "回款");

    @Override
    public String engineKey() {
        return "three-flows";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double infoDelay = doubleParam(params, "info_delay_hours", 0, 48, errors);
        intParam(params, "settlement_days", 1, 90, errors);
        intParam(params, "logistics_days", 1, 30, errors);
        doubleParam(params, "inventory_turnover", 4, 52, errors);
        doubleParam(params, "info_accuracy", 0.9, 1.0, errors);
        // 约束 info_lag_limit：信息流不能滞后物流超过 24 小时
        if (errors.isEmpty() && infoDelay != null && infoDelay > 24) {
            errors.add("info_lag_limit 约束不满足：信息流滞后不能超过 24 小时（当前 " + infoDelay + " 小时）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double infoDelay = ((Number) params.get("info_delay_hours")).doubleValue() / 24.0;
        int settlementDays = ((Number) params.get("settlement_days")).intValue();
        int logisticsDays = ((Number) params.get("logistics_days")).intValue();
        double turnover = ((Number) params.get("inventory_turnover")).doubleValue();
        double accuracy = ((Number) params.get("info_accuracy")).doubleValue();

        // 步骤 1：三流时间线构建（各阶段累计天数）
        double transport = logisticsDays * 0.8;                      // 在途运输占物流周期 80%
        double rework = (1 - accuracy) * logisticsDays * 0.5;        // 信息错误引发的返工
        double[] info = {0.2, 0.7, 0.8, 1.3, 1.8, 2.0, 2.2};
        double[] logistics = {0.2, 0.7, 1.7, 1.7 + transport, 2.2 + transport, 2.2 + transport, 2.2 + transport};
        double[] cash = {0, 0, 0, 0, 0, settlementDays, settlementDays + 1};
        List<Double> infoCum = new ArrayList<>();
        List<Double> logisticsCum = new ArrayList<>();
        List<Double> cashCum = new ArrayList<>();
        for (int i = 0; i < STAGES.size(); i++) {
            infoCum.add(round2(info[i] + infoDelay + (i >= 2 ? rework : 0)));
            logisticsCum.add(round2(logistics[i]));
            cashCum.add(round2(cash[i]));
        }
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("x", STAGES);
        timeline.put("series", List.of(
                Map.of("name", "信息流", "data", infoCum),
                Map.of("name", "物流", "data", logisticsCum),
                Map.of("name", "资金流", "data", cashCum)));
        ctx.step(String.format("三流时间线构建完成：信息流延迟 %.1f 天、物流执行 %d 天、结算周期 %d 天、信息准确率 %.0f%%（返工 %.1f 天）",
                infoDelay, logisticsDays, settlementDays, accuracy * 100, rework),
                Map.of("info_delay_days", round2(infoDelay), "logistics_days", logisticsDays,
                        "settlement_days", settlementDays, "rework_days", round2(rework)));

        // 步骤 2：订单-现金周期与现金流周转
        double orderToCash = 3.2 + transport + settlementDays + infoDelay + rework;
        double cashCycle = 365.0 / turnover + settlementDays;
        ctx.step(String.format("订单-现金周期 %.1f 天（信息 %.1f + 物流 %.1f + 资金 %d）；现金流周转天数 %.1f 天（库存周转 365/%.0f + 结算 %d 天）",
                orderToCash, infoDelay + rework, 2.4 + transport, settlementDays,
                cashCycle, turnover, settlementDays),
                Map.of("order_to_cash_days", round2(orderToCash), "cash_cycle_days", round2(cashCycle)));

        // 步骤 3：瓶颈流识别
        double infoTotal = infoCum.get(infoCum.size() - 1);
        double logisticsTotal = logisticsCum.get(logisticsCum.size() - 1);
        double cashTotal = cashCum.get(cashCum.size() - 1);
        String bottleneck;
        double bottleneckDays;
        if (cashTotal >= logisticsTotal && cashTotal >= infoTotal) {
            bottleneck = "资金流";
            bottleneckDays = cashTotal;
        } else if (logisticsTotal >= infoTotal) {
            bottleneck = "物流";
            bottleneckDays = logisticsTotal;
        } else {
            bottleneck = "信息流";
            bottleneckDays = infoTotal;
        }
        Map<String, Object> topo = new LinkedHashMap<>();
        topo.put("nodes", List.of(
                Map.of("id", "info", "name", "信息流", "type", "flow"),
                Map.of("id", "logistics", "name", "物流", "type", "flow"),
                Map.of("id", "cash", "name", "资金流", "type", "flow"),
                Map.of("id", "bottleneck", "name", "瓶颈：" + bottleneck, "type", "bottleneck")));
        topo.put("edges", List.of(
                Map.of("source", "info", "target", "bottleneck"),
                Map.of("source", "logistics", "target", "bottleneck"),
                Map.of("source", "cash", "target", "bottleneck")));
        ctx.step(String.format("瓶颈流识别：%s（%.1f 天）为最长路径，三流流速不匹配导致订单-现金周期拉长",
                bottleneck, bottleneckDays), Map.of("bottleneck", bottleneck, "bottleneck_days", round2(bottleneckDays)));

        // 步骤 4：三流协同结论
        double gap = Math.max(0, bottleneckDays - (infoTotal + logisticsTotal + cashTotal) / 3.0);
        ctx.step(String.format("三流协同建议：缩短%s（%d 天）可压缩订单-现金周期 %.0f 天；信息-物流-资金三流联动，流速匹配是协同关键",
                bottleneck, settlementDays, round2(gap)), Map.of("flow_gap", round2(gap)));

        // 输出指标（FR-007）
        ctx.output("three_flow_timeline", "三流时间线甘特图", "series", timeline, "天");
        ctx.output("cash_cycle_days", "现金流周转天数", "scalar", round2(cashCycle), "天");
        ctx.output("order_to_cash_days", "订单-现金周期", "scalar", round2(orderToCash), "天");
        ctx.output("bottleneck", "瓶颈流识别", "topo", topo, null);
    }
}
