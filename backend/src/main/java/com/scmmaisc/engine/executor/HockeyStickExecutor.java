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
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 曲棍球棒效应仿真执行器（T059，CH8-003）。
 * 模型：月考核制下日发货量模拟（月初平稳→月末集中冲量→次月初骤降+压货退货）→ 十项负面
 * 影响量化（加班溢价/库存/退货/运输/质量/流失/资金/排产/紧急物流/满意度）→ 四种改善策略
 * （平滑考核/滚动预测/取消月结/双月考核）对比 → 月度资源利用率波动。
 */
@Component
public class HockeyStickExecutor implements ScenarioExecutor {

    private static final double UNIT_PRICE = 30.0;   // 单价（元/件）
    private static final double HOLD_RATE = 1.0;     // 单位库存月持有成本（元/件·月）
    private static final double CAP_RATIO = 1.2;     // 设计产能 = 月均需求 × 1.2

    @Override
    public String engineKey() {
        return "hockey-stick";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "review_cycle", Set.of("weekly", "monthly", "quarterly"), errors);
        intParam(params, "monthly_demand", 1000, 10000, errors);
        Double ratio = doubleParam(params, "month_end_ratio", 0.3, 0.7, errors);
        doubleParam(params, "overtime_factor", 1.5, 3.0, errors);
        doubleParam(params, "return_ratio", 0.01, 0.05, errors);
        enumParam(params, "improve_strategy", Set.of("smooth_review", "rolling_forecast",
                "remove_monthly", "bi_monthly"), errors);
        if (errors.isEmpty() && ratio != null && ratio > 0.7) {
            errors.add("total_ok 约束不满足：月总发货量=月总需求（月末占比过高会放大负面效应）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 改善策略 → 有效月末冲量比例（压货扭曲程度）。 */
    private static double effectiveRatio(String strategy, double ratio) {
        return switch (strategy) {
            case "smooth_review" -> Math.max(0.1, ratio * 0.2);
            case "rolling_forecast" -> ratio * 0.5;
            case "remove_monthly" -> 0.05;
            case "bi_monthly" -> ratio * 0.7;
            default -> ratio;
        };
    }

    /** 给定冲量比例 r 的年度十项负面影响合计（元，#10 满意度为 %）。 */
    private static double[] impacts(double r, double d, double ot, double ret) {
        double peak = d * (r / 2 - 1.0 / 30);                 // 月末日均超出日均产能的部分
        double overtime = 12 * 2 * peak * 10 * ot;            // 1.加班溢价
        double inventory = 12 * d * r * HOLD_RATE;            // 2.库存积压
        double returns = 12 * d * ret * UNIT_PRICE * 0.5;     // 3.压货退货损失
        double transport = 12 * 2 * Math.ceil(peak / 500) * 800; // 4.运输集中成本
        double quality = overtime * 0.1;                      // 5.赶工质量损失
        double staff = 24 * 2000;                             // 6.人员流失
        double capital = 12 * d * r * UNIT_PRICE * 0.06;      // 7.资金占用
        double schedule = 12 * peak * 5;                      // 8.排产波动
        double urgent = 12 * 2 * 3000;                        // 9.紧急物流
        double satisfaction = 12 * 0.8 * 2;                   // 10.满意度损失(%)
        return new double[]{overtime, inventory, returns, transport, quality, staff,
                capital, schedule, urgent, satisfaction};
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String cycle = String.valueOf(params.get("review_cycle"));
        double d = ((Number) params.get("monthly_demand")).doubleValue();
        double ratio = ((Number) params.get("month_end_ratio")).doubleValue();
        double ot = ((Number) params.get("overtime_factor")).doubleValue();
        double ret = ((Number) params.get("return_ratio")).doubleValue();
        String improve = String.valueOf(params.get("improve_strategy"));
        double r = effectiveRatio(improve, ratio);
        String[] cycleLabels = {"周考核", "月考核", "季考核"};
        int ci = cycle.equals("weekly") ? 0 : cycle.equals("quarterly") ? 2 : 1;

        // 步骤 1：基线模型（考核周期 + 月末冲量）
        ctx.step(String.format("基线：%s（月自然需求 %,.0f 件），月末冲量占比 %.0f%% → 有效扭曲比例 %.0f%%",
                        cycleLabels[ci], d, ratio * 100, r * 100),
                Map.of("review_cycle", cycleLabels[ci], "effective_ratio", round2(r)));

        // 步骤 2：月度发货量曲线（日粒度 12 个月：平稳→月末冲量→次月初骤降）
        List<Double> shipX = new ArrayList<>();
        List<Double> shipY = new ArrayList<>();
        double normal = d * (1 - r) / 28;
        double surge = d * r / 2;
        for (int m = 0; m < 12; m++) {
            for (int day = 1; day <= 30; day++) {
                double v;
                if (day >= 29) {
                    v = surge;
                } else if (day == 1) {
                    v = normal * 0.3; // 冲量透支后骤降 + 压货退货
                } else {
                    v = normal;
                }
                shipX.add((double) (m * 30 + day));
                shipY.add(round2(v));
            }
        }
        ctx.step("12 个月日发货量模拟完成（月末集中冲量 → 次月初骤降的曲棍球棒形态）",
                Map.of("shipment_curve_points", shipY.size()));

        // 步骤 3：十项负面影响量化
        double[] imp = impacts(r, d, ot, ret);
        String[] names = {"加班溢价", "库存积压", "压货退货损失", "运输集中成本", "赶工质量损失",
                "人员流失成本", "资金占用成本", "排产波动成本", "紧急物流成本", "客户满意度损失"};
        List<Map<String, Object>> impactTable = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            impactTable.add(Map.of("name", names[i], "value", round2(imp[i])));
        }
        double totalImpact = 0;
        for (int i = 0; i < 9; i++) {
            totalImpact += imp[i];
        }
        ctx.step(String.format("十项负面影响量化完成（除满意度外合计 %,.0f 元/年）", totalImpact),
                Map.of("impact_table", impactTable, "total_impact", round2(totalImpact)));

        // 步骤 4：四种改善策略效果对比
        String[] strategies = {"smooth_review", "rolling_forecast", "remove_monthly", "bi_monthly"};
        String[] stratNames = {"平滑考核", "滚动预测", "取消月结", "双月考核"};
        List<Map<String, Object>> strategyCompare = new ArrayList<>();
        strategyCompare.add(Map.of("name", "基线", "value", round2(totalImpact)));
        double chosenTotal = 0;
        String chosenName = "";
        for (int i = 0; i < strategies.length; i++) {
            double r2 = effectiveRatio(strategies[i], ratio);
            double[] im2 = impacts(r2, d, ot, ret);
            double tot = 0;
            for (int j = 0; j < 9; j++) {
                tot += im2[j];
            }
            strategyCompare.add(Map.of("name", stratNames[i], "value", round2(tot)));
            if (strategies[i].equals(improve)) {
                chosenTotal = tot;
                chosenName = stratNames[i];
            }
        }
        ctx.step(String.format("改善策略对比完成；当前策略「%s」年度负面影响 %,.0f 元，较基线降低 %,.1f%%",
                        chosenName, chosenTotal, (1 - chosenTotal / totalImpact) * 100),
                Map.of("strategy_compare", strategyCompare));

        // 步骤 5：月度资源利用率波动（月内赶工加权工作量 / 设计产能）
        List<Double> utilX = new ArrayList<>();
        List<Double> utilY = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            double work = d * (1 - r) + d * r * ot;
            double util = work / (d * CAP_RATIO) * 100;
            if (m == 1) {
                util += ret * 100; // 首月含退货处理
            }
            utilX.add((double) m);
            utilY.add(round2(Math.min(160, util)));
        }
        ctx.step("月度资源利用率波动完成（冲量月加班赶工利用率显著高于设计产能）",
                Map.of("resource_utilization", utilY));

        // 输出指标（FR-007）
        ctx.output("shipment_curve", "月度发货量曲线", "series",
                series(shipX, "日发货量(件)", shipY), "件");
        ctx.output("impact_table", "十项负面影响量化表", "compare", impactTable, null);
        ctx.output("strategy_compare", "改善策略效果对比", "compare", strategyCompare, null);
        ctx.output("resource_utilization", "月度资源利用率波动", "series",
                series(utilX, "资源利用率(%)", utilY), "%");
    }
}
