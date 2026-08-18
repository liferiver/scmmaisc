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
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 冷链物流温控与断链预警仿真执行器（T054，CH3-006）。
 * 模型：设定温区目标温度，IoT 传感器按采样间隔持续采集；设备按小时故障概率随机
 * 失效（温升向环境温度逼近，持续至响应完成）、装卸环节按暴露时长脱离温控 → 偏离
 * 黄/红阈值触发断链预警 → 超阈值时间平方累积质量衰减 → 运输/装卸/暂存/验收 四环节
 * × 四风险因子热图与货损率。随机性来自 ctx.random()，种子可复现（FR-008）。
 */
@Component
public class ColdChainExecutor implements ScenarioExecutor {

    private static final Set<String> TEMP_ZONES = Set.of("deep_frozen", "frozen", "chilled", "thermostatic");
    private static final List<String> STAGES = List.of("运输", "装卸", "暂存", "验收");
    private static final List<String> RISK_FACTORS = List.of("温控设备", "监测覆盖", "响应速度", "包装防护");
    private static final int JOURNEY_MINUTES = 480;   // 全程 8 小时
    private static final double NOISE_SIGMA = 0.4;    // 正常温控波动（°C）

    @Override
    public String engineKey() {
        return "cold-chain";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "temp_zone", TEMP_ZONES, errors);
        doubleParam(params, "sample_interval", 10, 300, errors);
        Object raw = params.get("ambient_temp");
        if (raw instanceof Map<?, ?> group && group.get("distType") != null) {
            String type = String.valueOf(group.get("distType"));
            if (!"normal".equals(type)) {
                errors.add("ambient_temp.distType 仅支持 normal");
            } else if (group.get("mean") == null || group.get("sigma") == null) {
                errors.add("ambient_temp.mean/sigma 缺失");
            }
        }
        doubleParam(params, "exposure_minutes", 5, 60, errors);
        doubleParam(params, "failure_prob", 0.001, 0.05, errors);
        Double yellow = doubleParam(params, "yellow_threshold", 1, 5, errors);
        Double red = doubleParam(params, "red_threshold", 3, 10, errors);
        Double response = doubleParam(params, "response_minutes", 5, 60, errors);
        // 约束 threshold_ok：黄线必须小于红线
        if (errors.isEmpty() && yellow != null && red != null && yellow >= red) {
            errors.add("threshold_ok 约束不满足：yellow_threshold (" + yellow + ") 必须小于 red_threshold (" + red + ")");
        }
        // 约束 break_total_ok：断链累计时长需小于 30 分钟（药品）
        if (errors.isEmpty() && response != null && response > 30) {
            errors.add("break_total_ok 约束不满足：response_minutes (" + response + ") 必须 ≤ 30");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String tempZone = String.valueOf(params.get("temp_zone"));
        double sampleInterval = ((Number) params.get("sample_interval")).doubleValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> ambient = (Map<String, Object>) params.get("ambient_temp");
        double ambientMean = ((Number) ambient.get("mean")).doubleValue();
        double ambientSigma = ((Number) ambient.get("sigma")).doubleValue();
        double exposureMinutes = ((Number) params.get("exposure_minutes")).doubleValue();
        double failureProb = ((Number) params.get("failure_prob")).doubleValue();
        double yellowThreshold = ((Number) params.get("yellow_threshold")).doubleValue();
        double redThreshold = ((Number) params.get("red_threshold")).doubleValue();
        double responseMinutes = ((Number) params.get("response_minutes")).doubleValue();

        double target = switch (tempZone) {
            case "deep_frozen" -> -25.0;
            case "chilled" -> 3.0;
            case "thermostatic" -> 20.0;
            default -> -18.0;   // frozen
        };
        int samples = (int) Math.round(JOURNEY_MINUTES * 60.0 / sampleInterval);
        int downsample = Math.max(1, samples / 96);

        // 步骤 1：温区与监控配置
        ctx.step(String.format("产品温区 %s（目标 %.0f°C）：采样间隔 %.0f s、环境温度 N(%.0f, %.0f)°C、"
                        + "装卸暴露 %.0f min、设备故障率 %.3f/h、黄线 ±%.1f°C / 红线 ±%.1f°C、响应 %.0f min",
                tempZone, target, sampleInterval, ambientMean, ambientSigma, exposureMinutes,
                failureProb, yellowThreshold, redThreshold, responseMinutes),
                Map.of("target_temp", target, "sample_interval", sampleInterval));

        // 步骤 2：全程温度仿真（正常波动 + 设备故障 + 装卸暴露）
        double cumulativeDecay = 0;
        double breakMinutes = 0;
        int yellowEvents = 0;
        int redEvents = 0;
        boolean failureActive = false;
        double failStartMin = -1;
        double failUntilMin = -1;
        boolean inBreak = false;
        double breakStartMin = 0;
        String breakSeverity = "";
        List<Double> tX = new ArrayList<>();
        List<Double> tY = new ArrayList<>();
        List<Double> dX = new ArrayList<>();
        List<Double> dY = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            double minute = i * sampleInterval / 60.0;
            if (!failureActive && ctx.random().nextDouble() < failureProb * sampleInterval / 3600.0) {
                failureActive = true;
                failStartMin = minute;
                failUntilMin = minute + responseMinutes;
            }
            if (failureActive && minute >= failUntilMin) {
                failureActive = false;
            }
            double temp = target + ctx.random().nextGaussian() * NOISE_SIGMA;
            if (failureActive) {
                double elapsed = minute - failStartMin;
                temp = target + (ambientMean - target) * Math.min(1.0, elapsed / 30.0)
                        + ctx.random().nextGaussian() * 0.5;
            }
            if (minute >= 240 && minute < 240 + exposureMinutes) {
                temp = target + (ambientMean - target) * Math.min(1.0, exposureMinutes / 30.0)
                        + ctx.random().nextGaussian() * 0.5;
            }
            double deviation = Math.abs(temp - target);
            // 断链检测（偏离超过黄线即计入断链）
            if (deviation > yellowThreshold) {
                if (!inBreak) {
                    inBreak = true;
                    breakStartMin = minute;
                    breakSeverity = deviation > redThreshold ? "red" : "yellow";
                    if (deviation > redThreshold) {
                        redEvents++;
                    } else {
                        yellowEvents++;
                    }
                }
                breakMinutes += sampleInterval / 60.0;
            } else if (inBreak) {
                inBreak = false;
                events.add(Map.of("start", round2(breakStartMin), "duration", round2(minute - breakStartMin),
                        "severity", breakSeverity));
            }
            // 质量衰减：超黄线部分按（超出量/红线）² 累积
            double excess = Math.max(0, deviation - yellowThreshold);
            cumulativeDecay += Math.pow(excess / redThreshold, 2) * 0.0005 * sampleInterval / 60.0;
            if (i % downsample == 0 || i == samples - 1) {
                tX.add(round2(minute / 60.0));
                tY.add(round2(temp));
                dX.add(round2(minute / 60.0));
                dY.add(round2(cumulativeDecay));
            }
        }
        if (inBreak) {
            events.add(Map.of("start", round2(breakStartMin),
                    "duration", round2(JOURNEY_MINUTES - breakStartMin), "severity", breakSeverity));
        }
        ctx.step(String.format("全程仿真完成（%d 个采样点）：断链事件 %d 次（黄 %d / 红 %d），"
                        + "累计断链 %.1f 分钟；设备故障 %s",
                samples, events.size(), yellowEvents, redEvents, breakMinutes,
                failureActive ? "处理中" : "已恢复/未发生"),
                Map.of("break_minutes", round2(breakMinutes), "events", events.size()));

        // 步骤 3：质量衰减曲线
        ctx.step(String.format("质量衰减指数 %.4f（超出黄线时间按平方累积，断链越久衰减越快）；"
                        + "HACCP 要求断链及时响应，响应时间 %.0f 分钟",
                cumulativeDecay, responseMinutes),
                Map.of("quality_decay", round2(cumulativeDecay * 10000) / 10000.0));

        // 步骤 4：断链风险热图与货损评估
        double deviceRisk = Math.min(100, failureProb * 3333.0);
        double monitorRisk = Math.min(100, (sampleInterval - 10) / 290.0 * 60.0);
        double responseRisk = Math.min(100, responseMinutes / 60.0 * 100.0);
        double packageRisk = switch (tempZone) {
            case "deep_frozen" -> 30;
            case "frozen" -> 40;
            case "chilled" -> 55;
            default -> 45;
        };
        double[] riskBase = {deviceRisk, monitorRisk, responseRisk, packageRisk};
        double[] stageMult = {1.0, 1.6, 1.2, 0.8};
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", STAGES);
        heatmap.put("columns", RISK_FACTORS);
        List<List<Double>> data = new ArrayList<>();
        for (double mult : stageMult) {
            List<Double> row = new ArrayList<>();
            for (double base : riskBase) {
                row.add(Math.min(100, round2(base * mult)));
            }
            data.add(row);
        }
        heatmap.put("data", data);
        double lossRate = Math.min(100, cumulativeDecay * 120 + exposureMinutes / 60.0 * 0.2
                + redEvents * 0.5 + yellowEvents * 0.1);
        ctx.step(String.format("货损率 %.2f%%（质量衰减 %.4f × 120 + 装卸暴露 %.2f%% + 红/黄断链惩罚）；"
                        + "风险热图显示装卸环节风险最高（×1.6）",
                lossRate, cumulativeDecay, exposureMinutes / 60.0 * 0.2),
                Map.of("loss_rate", round2(lossRate), "risk_hotspot", "装卸环节"));

        Map<String, Object> tempSeries = new LinkedHashMap<>();
        tempSeries.put("x", tX);
        tempSeries.put("series", List.of(Map.of("name", "货温(°C)", "data", tY)));
        Map<String, Object> decaySeries = new LinkedHashMap<>();
        decaySeries.put("x", dX);
        decaySeries.put("series", List.of(Map.of("name", "质量衰减指数", "data", dY)));
        Map<String, Object> breakSeries = new LinkedHashMap<>();
        List<Double> bx = new ArrayList<>();
        List<Double> by = new ArrayList<>();
        for (int k = 0; k < events.size(); k++) {
            bx.add((double) (k + 1));
            by.add(((Number) events.get(k).get("duration")).doubleValue());
        }
        breakSeries.put("x", bx);
        breakSeries.put("series", List.of(Map.of("name", "断链时长(分钟)", "data", by)));

        // 输出指标（FR-007）
        ctx.output("temp_curve", "全程温度曲线", "series", tempSeries, "°C");
        ctx.output("break_events", "断链事件次数与时长", "series", breakSeries, "分钟");
        ctx.output("quality_decay", "质量衰减指数", "series", decaySeries, null);
        ctx.output("risk_heatmap", "断链风险点热力图", "heatmap", heatmap, null);
        ctx.output("loss_rate", "货损率", "scalar", round2(lossRate), "%");
    }
}
