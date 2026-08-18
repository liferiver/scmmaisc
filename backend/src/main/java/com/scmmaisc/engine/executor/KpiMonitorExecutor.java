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
 * 物流 KPI 绩效监控预警仿真执行器（T053，CH2-008）。
 * 模型：四维度 KPI（成本/时间/质量/效率）目标 + 黄/红预警线 → 按考核期注入实际数据
 * （目标 × 随机扰动，ctx.random() 种子可复现）→ 计算达成率与综合得分 → 超线触发预警 →
 * 输出预警时间线与趋势分析。
 */
@Component
public class KpiMonitorExecutor implements ScenarioExecutor {

    private static final List<String> KPI_NAMES = List.of("订单准时率", "货物完好率", "车辆利用率", "库存周转率", "单均物流成本", "订单处理时长");

    @Override
    public String engineKey() {
        return "kpi-monitor";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "target_ontime", 0.95, 0.99, errors);
        doubleParam(params, "target_integrity", 0.98, 0.999, errors);
        doubleParam(params, "target_vehicle_util", 0.70, 0.90, errors);
        intParam(params, "target_turnover", 12, 52, errors);
        doubleParam(params, "target_unit_cost", 5, 100, errors);
        doubleParam(params, "target_processing_hours", 1, 48, errors);
        intParam(params, "periods", 12, 52, errors);
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double targetOntime = ((Number) params.get("target_ontime")).doubleValue();
        double targetIntegrity = ((Number) params.get("target_integrity")).doubleValue();
        double targetUtil = ((Number) params.get("target_vehicle_util")).doubleValue();
        int targetTurnover = ((Number) params.get("target_turnover")).intValue();
        double targetUnitCost = ((Number) params.get("target_unit_cost")).doubleValue();
        double targetHours = ((Number) params.get("target_processing_hours")).doubleValue();
        int periods = ((Number) params.get("periods")).intValue();

        // 目标/黄线/红线（成本与时间为越低越好，其余越高越好）
        double[] target = {targetOntime, targetIntegrity, targetUtil, targetTurnover, targetUnitCost, targetHours};
        double[] yellow = {target[0] - 0.02, target[1] - 0.01, target[2] - 0.10,
                target[3] * 0.8, target[4] * 1.10, target[5] * 1.20};
        double[] red = {target[0] - 0.05, target[1] - 0.03, target[2] - 0.20,
                target[3] * 0.6, target[4] * 1.25, target[5] * 1.50};
        double[] amp = {0.012, 0.005, 0.03, 1.2, 0.05 * target[4], 0.08 * target[5]};
        boolean[] lowerBetter = {false, false, false, false, true, true};

        // 步骤 1：KPI 指标集与预警阈值
        ctx.step(String.format("KPI 体系（四维度）：质量 准时率 %.1f%%/完好率 %.2f%%、效率 车辆利用率 %.0f%%/周转率 %d 次、"
                        + "成本 单均 %.0f 元、时间 处理 %.0f 小时；每项设黄线/红线两级预警",
                targetOntime * 100, targetIntegrity * 100, targetUtil * 100, targetTurnover,
                targetUnitCost, targetHours),
                Map.of("periods", periods));

        // 步骤 2：逐期注入实际数据（随机扰动，种子可复现）并统计预警
        double[] scoreSum = new double[6];
        int[] yellowCount = new int[6];
        int[] redCount = new int[6];
        List<Double> alarmX = new ArrayList<>();
        List<Double> alarmY = new ArrayList<>();
        List<Double> trendX = new ArrayList<>();
        List<Double> trendY = new ArrayList<>();
        for (int period = 1; period <= periods; period++) {
            double periodScore = 0;
            int periodAlarms = 0;
            for (int k = 0; k < 6; k++) {
                double actual = target[k] + (ctx.random().nextDouble() * 2 - 1) * amp[k];
                double achievement;
                if (lowerBetter[k]) {
                    achievement = Math.min(100, Math.max(0, target[k] / actual * 100));
                } else {
                    achievement = Math.min(100, Math.max(0, actual / target[k] * 100));
                }
                scoreSum[k] += achievement;
                periodScore += achievement / 6.0;
                if ((!lowerBetter[k] && actual < red[k]) || (lowerBetter[k] && actual > red[k])) {
                    redCount[k]++;
                    periodAlarms += 2;
                } else if ((!lowerBetter[k] && actual < yellow[k]) || (lowerBetter[k] && actual > yellow[k])) {
                    yellowCount[k]++;
                    periodAlarms += 1;
                }
            }
            alarmX.add((double) period);
            alarmY.add((double) periodAlarms);
            trendX.add((double) period);
            trendY.add(round2(periodScore));
        }
        Map<String, Object> alarmSeries = new LinkedHashMap<>();
        alarmSeries.put("x", alarmX);
        alarmSeries.put("series", List.of(Map.of("name", "预警事件数", "data", alarmY)));
        Map<String, Object> trendSeries = new LinkedHashMap<>();
        trendSeries.put("x", trendX);
        trendSeries.put("series", List.of(Map.of("name", "综合绩效得分", "data", trendY)));
        int totalAlarms = 0;
        for (int k = 0; k < 6; k++) {
            totalAlarms += yellowCount[k] + redCount[k];
        }
        ctx.step(String.format("仿真完成：%d 期数据注入，共触发预警 %d 次（黄 %d、红 %d）",
                periods, totalAlarms, sum(yellowCount), sum(redCount)),
                Map.of("total_alarms", totalAlarms, "yellow", sum(yellowCount), "red", sum(redCount)));

        // 步骤 3：综合绩效得分与达成率仪表盘
        double overall = 0;
        for (int k = 0; k < 6; k++) {
            overall += scoreSum[k] / 6.0 / periods * 100;
        }
        List<Map<String, Object>> gauges = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            gauges.add(Map.of("name", KPI_NAMES.get(k), "value", round2(scoreSum[k] / periods)));
        }
        ctx.step(String.format("综合绩效得分 %.1f 分；各 KPI 达成率：%s",
                overall, gaugeSummary(gauges)), Map.of("overall_score", round2(overall)));

        // 步骤 4：预警分析与改善建议
        StringBuilder advice = new StringBuilder();
        for (int k = 0; k < 6; k++) {
            if (redCount[k] > 0) {
                advice.append(KPI_NAMES.get(k)).append(" 触发红预警 ").append(redCount[k]).append(" 次；");
            }
        }
        String adviceText = advice.length() == 0 ? "全部 KPI 处于受控区间，无需干预" : advice.toString();
        ctx.step("预警分析：" + adviceText, Map.of("advice", adviceText));

        // 输出指标（FR-007）
        ctx.output("overall_score", "综合绩效得分", "scalar", round2(overall), "分");
        ctx.output("kpi_gauges", "各KPI达成率仪表盘", "gauge", gauges, "%");
        ctx.output("alarm_timeline", "预警事件时间线", "series", alarmSeries, "次");
        ctx.output("trend", "KPI趋势分析", "series", trendSeries, "分");
    }

    private int sum(int[] arr) {
        int s = 0;
        for (int v : arr) {
            s += v;
        }
        return s;
    }

    private String gaugeSummary(List<Map<String, Object>> gauges) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> g : gauges) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(g.get("name")).append(" ").append(g.get("value")).append("%");
        }
        return sb.toString();
    }
}
