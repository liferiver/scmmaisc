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
 * 电商物流三化（信息化/自动化/柔性化）特征仿真执行器（T055，CH4-001）。
 * 模型：基线人工产能 → 逐步投入信息化（订单自动处理）、自动化（分拣设备）、柔性化
 * （临时工/共享仓）→ 大促峰值（日常 ×peak_multiplier）测试各阶段峰值应对能力；
 * 订单处理/分拣/包装/配送 四环节产能取最小为系统吞吐，识别瓶颈环节。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class EcommerceThreeExecutor implements ScenarioExecutor {

    private static final double MANUAL_WORKERS = 200.0;   // 基线人工数（人）
    private static final double AUTO_SORT_PER_1000W = 2000.0;  // 每 1000 万元投资的自动分拣产能（单/h）

    @Override
    public String engineKey() {
        return "ecommerce-three";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "daily_orders", 500, 50000, errors);
        doubleParam(params, "peak_multiplier", 3, 20, errors);
        Double info = doubleParam(params, "info_coverage", 0.3, 1.0, errors);
        doubleParam(params, "automation_invest", 100, 5000, errors);
        Double flex = doubleParam(params, "flexibility", 0, 1.0, errors);
        doubleParam(params, "manual_handle_minutes", 3, 10, errors);
        // 约束 info_flex_ok：信息化与柔性化合计需 ≥ 0.8，否则峰值期积压可能超过 2 天
        if (errors.isEmpty() && info != null && flex != null && info + flex < 0.8) {
            errors.add("info_flex_ok 约束不满足：info_coverage + flexibility (" + round2(info + flex)
                    + ") 必须 ≥ 0.8");
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
        double peakMultiplier = ((Number) params.get("peak_multiplier")).doubleValue();
        double infoCoverage = ((Number) params.get("info_coverage")).doubleValue();
        double automationInvest = ((Number) params.get("automation_invest")).doubleValue();
        double flexibility = ((Number) params.get("flexibility")).doubleValue();
        double manualMinutes = ((Number) params.get("manual_handle_minutes")).doubleValue();

        double manualCap = MANUAL_WORKERS * 60.0 / manualMinutes;      // 人工产能（单/h）
        double infoCap = manualCap * (1 + infoCoverage * 2.0);          // +信息化
        double sortCap = manualCap + automationInvest / 1000.0 * AUTO_SORT_PER_1000W;  // +自动化分拣
        double packCap = manualCap * (1 + flexibility * 0.8);           // +柔性化包装
        double delivCap = manualCap * (1 + flexibility);                // +柔性化配送
        double throughput = min4(infoCap, sortCap, packCap, delivCap);
        double peakRate = dailyOrders * peakMultiplier / 24.0;

        // 步骤 1：基线场景与三化配置
        ctx.step(String.format("基线：日订单 %d 单、人工 %d 人、每单 %.1f 分钟 → 人工产能 %.0f 单/h；"
                        + "三化投入：信息化 %.0f%%、自动化 %.0f 万元、柔性化 %.0f%%",
                dailyOrders, (int) MANUAL_WORKERS, manualMinutes, manualCap,
                infoCoverage * 100, automationInvest, flexibility * 100),
                Map.of("daily_orders", dailyOrders, "manual_cap", round2(manualCap)));

        // 步骤 2：三化逐级投入的产能与峰值应对能力
        double baseMultiple = manualCap / dailyOrders;
        double infoMultiple = infoCap / dailyOrders;
        double autoMultiple = Math.min(infoCap, sortCap) / dailyOrders;
        double fullMultiple = throughput / dailyOrders;
        List<Map<String, Object>> investItems = new ArrayList<>();
        investItems.add(Map.of("name", "纯人工基线", "value", round2(baseMultiple)));
        investItems.add(Map.of("name", "+信息化", "value", round2(infoMultiple)));
        investItems.add(Map.of("name", "+自动化", "value", round2(autoMultiple)));
        investItems.add(Map.of("name", "三化齐全", "value", round2(fullMultiple)));
        ctx.step(String.format("峰值应对能力（可承接倍数 = 产能/日均单量）：纯人工 %.1f 倍 → 三化齐全 %.1f 倍"
                        + "（本次大促峰值 %.0f 倍）",
                baseMultiple, fullMultiple, peakMultiplier),
                Map.of("full_multiple", round2(fullMultiple), "peak_multiplier", peakMultiplier));

        // 步骤 3：大促峰值测试与处理时效（14 天促销期）
        String bottleneck;
        double bnCap = Math.min(infoCap, Math.min(sortCap, Math.min(packCap, delivCap)));
        if (bnCap == infoCap) {
            bottleneck = "订单处理";
        } else if (bnCap == sortCap) {
            bottleneck = "自动分拣";
        } else if (bnCap == packCap) {
            bottleneck = "包装";
        } else {
            bottleneck = "配送";
        }
        double backlogDays = Math.max(0, (peakRate - throughput) / throughput);
        double baselineHours = manualMinutes / 60.0 * (1 - 0.3 * infoCoverage);
        double serviceTime = baselineHours + 24.0 * Math.min(2, backlogDays);
        List<Double> dx = new ArrayList<>();
        List<Double> dy = new ArrayList<>();
        for (int day = 1; day <= 14; day++) {
            double surge = 1 + 1.0 * Math.exp(-(day - 1) / 2.5);
            double rateDay = dailyOrders * peakMultiplier * surge / 24.0;
            double backlog = Math.max(0, (rateDay - throughput) / throughput);
            dx.add((double) day);
            dy.add(round2(baselineHours + 24.0 * Math.min(2, backlog)));
        }
        Map<String, Object> timeSeries = new LinkedHashMap<>();
        timeSeries.put("x", dx);
        timeSeries.put("series", List.of(Map.of("name", "峰值期处理时效(小时)", "data", dy)));
        ctx.step(String.format("大促峰值 %.0f 单/h vs 系统吞吐 %.0f 单/h：积压 %.1f 天，"
                        + "峰值处理时效 %.1f 小时（瓶颈：%s）",
                peakRate, throughput, backlogDays, serviceTime, bottleneck),
                Map.of("peak_service_time", round2(serviceTime), "bottleneck", bottleneck));

        // 步骤 4：瓶颈识别与峰值期单位成本
        double peakUnitCost = 2.0 * (1 - 0.3 * infoCoverage - 0.4 * Math.min(1, automationInvest / 2000.0))
                * (1 + 0.5 * Math.min(2, backlogDays));
        ctx.step(String.format("峰值期单位成本 %.2f 元/单（人工 2 元基线 − 信息化 %.0f%% − 自动化 %.0f%%"
                        + " + 积压加班 %.0f%%）；瓶颈 %s 限制整体吞吐",
                peakUnitCost, 0.3 * infoCoverage * 100, 0.4 * Math.min(1, automationInvest / 2000.0) * 100,
                0.5 * Math.min(2, backlogDays) * 100, bottleneck),
                Map.of("peak_unit_cost", round2(peakUnitCost)));

        Map<String, Object> topo = new LinkedHashMap<>();
        topo.put("nodes", List.of(
                Map.of("id", "order", "name", "订单处理", "type", "stage"),
                Map.of("id", "sort", "name", "自动分拣", "type", "stage"),
                Map.of("id", "pack", "name", "包装", "type", "stage"),
                Map.of("id", "deliv", "name", "配送", "type", "stage"),
                Map.of("id", "bn", "name", "瓶颈：" + bottleneck, "type", "bottleneck")));
        topo.put("edges", List.of(
                Map.of("source", "order", "target", "sort"),
                Map.of("source", "sort", "target", "pack"),
                Map.of("source", "pack", "target", "deliv"),
                Map.of("source", "order", "target", "bn"),
                Map.of("source", "sort", "target", "bn"),
                Map.of("source", "pack", "target", "bn"),
                Map.of("source", "deliv", "target", "bn")));

        // 输出指标（FR-007）
        ctx.output("peak_service_time", "峰值期处理时效", "series", timeSeries, "小时");
        ctx.output("investment_response", "三化投资-峰值应对能力曲线", "compare", investItems, "倍");
        ctx.output("bottlenecks", "瓶颈环节识别", "topo", topo, null);
        ctx.output("peak_unit_cost", "峰值期单位成本", "scalar", round2(peakUnitCost), "元/单");
    }

    private double min4(double a, double b, double c, double d) {
        return Math.min(a, Math.min(b, Math.min(c, d)));
    }
}
