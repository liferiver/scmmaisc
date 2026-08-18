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
 * 条码/RFID 信息自动采集与追溯仿真执行器（T054，CH3-001）。
 * 模型：四种识别技术（一维条码/二维码/RFID 高频/超高频）在通过速率、读取距离、
 * 环境干扰、标签成本、人工扫码时间下的识别成功率与漏读率；条码需逐件人工对准
 * （受 scan_time 产能上限约束），RFID 批量自动读取（受距离/干扰/速率影响）→
 * 处理量对比 + 全链路追溯完整度 + 单件成本效率对比。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class BarcodeRfidExecutor implements ScenarioExecutor {

    private static final Set<String> TECH_TYPES = Set.of("one_dim_barcode", "qr_code", "rfid_hf", "rfid_uhf");
    private static final double LABOR_RATE = 30.0;   // 人工时薪（元/小时）

    @Override
    public String engineKey() {
        return "barcode-rfid";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "tech_type", TECH_TYPES, errors);
        Double throughput = doubleParam(params, "throughput_rate", 10, 600, errors);
        doubleParam(params, "read_distance", 0.1, 10, errors);
        intParam(params, "interference", 1, 5, errors);
        doubleParam(params, "tag_cost", 0.01, 2, errors);
        doubleParam(params, "scan_time", 1, 5, errors);
        // 约束 recognition_ok：通过速率过高将导致漏读
        if (errors.isEmpty() && throughput != null && throughput > 600) {
            errors.add("recognition_ok 约束不满足：throughput_rate (" + throughput + ") 必须 ≤ 600 件/分钟");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String techType = String.valueOf(params.get("tech_type"));
        double throughputRate = ((Number) params.get("throughput_rate")).doubleValue();
        double readDistance = ((Number) params.get("read_distance")).doubleValue();
        int interference = ((Number) params.get("interference")).intValue();
        double tagCost = ((Number) params.get("tag_cost")).doubleValue();
        double scanTime = ((Number) params.get("scan_time")).doubleValue();
        boolean rfid = techType.startsWith("rfid");

        // 步骤 1：技术选型与识别原理
        ctx.step(String.format("识别技术：%s（%s）——%s",
                techName(techType), techType,
                rfid ? "RFID 批量自动读取、无需逐件对准，读取距离与环境干扰影响显著"
                        : "条码逐件扫码需人工对准，识别速率受单件扫码时间上限约束"),
                Map.of("tech_type", techType, "throughput_rate", throughputRate));

        // 步骤 2：识别成功率与漏读率
        double recognition;
        double processing;
        if (rfid) {
            double base = "rfid_uhf".equals(techType) ? 99.9 : 99.8;
            double maxDist = "rfid_uhf".equals(techType) ? 8.0 : 1.0;
            double distFactor = readDistance <= maxDist ? 1.0 : Math.max(0.80, 1.0 - (readDistance - maxDist) * 0.05);
            double interFactor = Math.max(0.85, 1.0 - (interference - 1) * 0.02);
            double rateFactor = throughputRate <= 400 ? 1.0 : Math.max(0.90, 1.0 - (throughputRate - 400) / 600.0 * 0.10);
            recognition = base * distFactor * interFactor * rateFactor;
            processing = throughputRate;   // 批量读取：处理量 = 通过速率
        } else {
            double base = "qr_code".equals(techType) ? 99.6 : 99.5;
            double maxManual = 60.0 / scanTime;   // 人工扫码产能上限（件/分钟）
            recognition = throughputRate <= maxManual ? base : base * maxManual / throughputRate;
            processing = maxManual;
        }
        double missRate = 100 - recognition;
        ctx.step(String.format("识别成功率 %.2f%%（漏读率 %.2f%%）：%s",
                recognition, missRate,
                rfid ? "RFID 免对准批量读取成功率高；距离/干扰/高速通过为衰减主因"
                        : "条码识别率受人工扫码速率制约，超出产能上限后按比例漏读"),
                Map.of("recognition_rate", round2(recognition), "miss_rate", round2(missRate)));

        // 步骤 3：单位时间处理量对比（条码逐件 vs RFID 批量）
        double barcodeRate = 60.0 / scanTime;
        ctx.step(String.format("处理量对比：条码人工 %.0f 件/分钟 vs RFID 批量 %.0f 件/分钟"
                        + "（大促场景吞吐相差 %.1f 倍）",
                barcodeRate, throughputRate, throughputRate / Math.max(1, barcodeRate)),
                Map.of("processing_rate", round2(processing), "barcode_rate", round2(barcodeRate)));

        // 步骤 4：全链路追溯完整度与成本-效率
        double trace = rfid
                ? Math.min(99.9, 98.0 + (recognition - 99.0) * 0.9)
                : Math.min(99.0, 90.0 + (recognition - 95.0) * 0.6);
        double laborPerItem = scanTime / 3600.0 * LABOR_RATE;
        List<Map<String, Object>> costItems = new ArrayList<>();
        costItems.add(Map.of("name", "一维条码", "value", round2(0.005 + laborPerItem)));
        costItems.add(Map.of("name", "二维码", "value", round2(0.010 + laborPerItem)));
        costItems.add(Map.of("name", "RFID高频", "value", round2(tagCost + 0.010)));
        costItems.add(Map.of("name", "RFID超高频", "value", round2(tagCost * 1.6 + 0.015)));
        ctx.step(String.format("追溯完整度 %.2f%%（RFID 自动上传 WMS/TMS 全链路可查，条码依赖扫码质量）；"
                        + "单件成本：条码 %.3f 元 vs RFID %.3f 元",
                trace, 0.005 + laborPerItem, tagCost + 0.010),
                Map.of("trace_completeness", round2(trace), "tag_cost", tagCost));

        // 输出指标（FR-007）
        ctx.output("recognition_rate", "识别成功率", "gauge", round2(recognition), "%");
        ctx.output("miss_rate", "漏读率", "scalar", round2(missRate), "%");
        ctx.output("processing_rate", "单位时间处理量", "scalar", round2(processing), "件/分钟");
        ctx.output("trace_completeness", "全链路追溯完整度", "gauge", round2(trace), "%");
        ctx.output("cost_efficiency", "成本-效率对比", "compare", costItems, "元/件");
    }

    private String techName(String techType) {
        return switch (techType) {
            case "one_dim_barcode" -> "一维条码";
            case "qr_code" -> "二维码";
            case "rfid_hf" -> "RFID高频";
            default -> "RFID超高频";
        };
    }
}
