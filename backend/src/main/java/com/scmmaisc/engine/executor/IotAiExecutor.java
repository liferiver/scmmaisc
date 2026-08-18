package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 物联网(IoT)与AI在物流中的应用仿真执行器（T062，CH11-009）。
 * 模型（轻量启发式）：物流资产（100 台车辆/仓储设备）年故障仿真——传统被动维护按基础故障率
 * （fault_rate_base）停机；IoT+AI 预测性维护按改善后故障率（fault_rate_pm，且提前干预缩短
 * 停机时长、AI 误报率 ai_false_rate 产生误干预成本）→ 停机时间/维护成本对比；数字孪生同步
 * 延迟（twin_sync_delay）与异常包裹检测准确率（anomaly_accuracy）→ 端到端可视化率、异常检出率、
 * IoT 投资 ROI（3 年回收期 vs 部署成本）。
 */
@Component
public class IotAiExecutor implements ScenarioExecutor {

    private static final int FLEET = 100;              // 物流资产数（车辆+仓储设备）
    private static final double MTTR = 8.0;            // 平均修复时长（小时）
    private static final double HOUR_LOSS = 15000.0;   // 停机每小时业务损失（元）
    private static final double REPAIR_COST = 5000.0;  // 单次维修成本（元）
    private static final double ANOMALY_VALUE = 200.0; // 单件异常包裹平均价值（元）

    @Override
    public String engineKey() {
        return "iot-ai";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "iot_coverage", 0, 1, errors);
        doubleParam(params, "sensor_interval", 1, 300, errors);
        doubleParam(params, "pm_accuracy", 0.7, 0.95, errors);
        Double baseRate = doubleParam(params, "fault_rate_base", 0.01, 0.05, errors);
        Double pmRate = doubleParam(params, "fault_rate_pm", 0.002, 0.01, errors);
        doubleParam(params, "anomaly_accuracy", 0.8, 0.99, errors);
        doubleParam(params, "twin_sync_delay", 1, 60, errors);
        doubleParam(params, "ai_false_rate", 0.01, 0.1, errors);
        if (errors.isEmpty() && baseRate != null && pmRate != null && pmRate >= baseRate) {
            errors.add("pm_effective 约束不满足：预测性维护应降低设备故障率（AI 预测水平需优于基线）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double coverage = ((Number) params.get("iot_coverage")).doubleValue();
        double interval = ((Number) params.get("sensor_interval")).doubleValue();
        double pmAcc = ((Number) params.get("pm_accuracy")).doubleValue();
        double baseRate = ((Number) params.get("fault_rate_base")).doubleValue();
        double pmRate = ((Number) params.get("fault_rate_pm")).doubleValue();
        double anomalyAcc = ((Number) params.get("anomaly_accuracy")).doubleValue();
        double twinDelay = ((Number) params.get("twin_sync_delay")).doubleValue();
        double aiFalse = ((Number) params.get("ai_false_rate")).doubleValue();

        // 步骤 1：传统物流基线（被动维护）
        double failuresBase = FLEET * baseRate;
        double downtimeBase = failuresBase * MTTR;
        double lossBase = downtimeBase * HOUR_LOSS + failuresBase * REPAIR_COST;
        ctx.step(String.format("传统被动维护：%d 台资产年故障 %.0f 次（故障率 %.1f%%），年停机 %.1f 小时，"
                        + "业务损失 %,.0f 元",
                FLEET, failuresBase, baseRate * 100, downtimeBase, lossBase),
                Map.of("failures_base", round2(failuresBase), "downtime_base", round2(downtimeBase)));

        // 步骤 2：IoT 部署与端到端可视化
        double visibility = coverage * 100;
        ctx.step(String.format("IoT 部署：资产数字化覆盖 %.0f%%（GPS/温湿度/能耗/RFID 传感器），"
                        + "数据采集频率 %.0f 秒/次 → 端到端可视化率 %.0f%%",
                coverage * 100, interval, visibility),
                Map.of("visibility_rate", round2(visibility), "sensor_interval", round2(interval)));

        // 步骤 3：数字孪生与实时监控
        ctx.step(String.format("数字孪生镜像同步延迟 %.0f 秒（虚实同步，支撑 what-if 模拟与异常预警）；"
                        + "异常包裹检测准确率 %.0f%%（AI 视觉/RFID 震动分析）",
                twinDelay, anomalyAcc * 100),
                Map.of("twin_sync_delay", round2(twinDelay), "anomaly_accuracy", round2(anomalyAcc * 100)));

        // 步骤 4：AI 预测性维护与异常检测
        double failuresPm = FLEET * pmRate;
        double downtimePm = failuresPm * MTTR * (1 - pmAcc * 0.6); // 提前干预缩短停机
        double lossPm = downtimePm * HOUR_LOSS + failuresPm * REPAIR_COST;
        double alerts = failuresPm * 20;
        double falseCost = alerts * aiFalse * 1500;
        double anomalySaving = 10000 * anomalyAcc * ANOMALY_VALUE * coverage; // 拦截异常包裹挽回
        double maintenanceSaving = (lossBase - lossPm) + anomalySaving - falseCost;
        ctx.step(String.format("AI 预测性维护：故障率降至 %.2f%%（%d 次），年停机 %.1f 小时（−%.1f%%）；"
                        + "AI 告警 %d 次（误报率 %.0f%% → 误干预成本 %,.0f 元）；异常包裹拦截挽回 %,.0f 元",
                pmRate * 100, (int) failuresPm, downtimePm, (1 - downtimePm / downtimeBase) * 100,
                (int) alerts, aiFalse * 100, falseCost, anomalySaving),
                Map.of("downtime_pm", round2(downtimePm), "maintenance_saving", round2(maintenanceSaving)));

        // 步骤 5：对比与 ROI
        List<Map<String, Object>> downtimeCompare = List.of(
                Map.of("name", "传统被动维护", "value", round2(downtimeBase)),
                Map.of("name", "IoT+AI预测维护", "value", round2(downtimePm)));
        double investment = coverage * 2000000 + (300 - interval) / 300.0 * 500000; // 部署+传感器
        double iotRoi = (3 * maintenanceSaving - investment) / investment * 100;
        ctx.step(String.format("年停机：%.1f → %.1f 小时；年维护+挽回净收益 %,.0f 元；IoT 总投资 %,.0f 元"
                        + "（覆盖 %.0f%% + 采集粒度），3 年回收期 ROI %.0f%%",
                downtimeBase, downtimePm, maintenanceSaving, investment, coverage * 100, iotRoi),
                Map.of("downtime_compare", downtimeCompare, "iot_roi", round2(iotRoi),
                        "maintenance_saving_final", round2(maintenanceSaving)));

        // 输出指标（FR-007）
        ctx.output("downtime_compare", "设备停机时间对比", "compare", downtimeCompare, "小时");
        ctx.output("maintenance_saving", "维护成本节省", "scalar", round2(maintenanceSaving), "元");
        ctx.output("anomaly_detection_rate", "异常检出率", "gauge", List.of(
                Map.of("name", "异常检出率", "value", round2(anomalyAcc * 100))), "%");
        ctx.output("visibility_rate", "端到端可视化率", "gauge", List.of(
                Map.of("name", "端到端可视化率", "value", round2(visibility))), "%");
        ctx.output("iot_roi", "IoT投资ROI", "scalar", round2(iotRoi), "%");
    }
}
