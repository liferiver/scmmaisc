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
 * EDI 电子数据交换单证流转仿真执行器（T054，CH3-003，角色扮演）。
 * 模型：采购订单经 内部格式→EDI 标准报文（EDIFACT/ANSI X12/XML/JSON）→VAN/互联网传输
 * → 供应方接收解析 → 订单确认 → ASN 发货通知 → 收货确认 → 发票流转 八环节；
 * 对比 EDI 与人工方式在差错率、端到端流转时间上的差异，并计算 ROI 回收期。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class EdiExecutor implements ScenarioExecutor {

    private static final Set<String> STANDARDS = Set.of("edifact", "ansi_x12", "xml", "json_api");
    private static final List<String> STAGES = List.of("采购订单生成", "格式转换", "传输", "接收解析",
            "订单确认", "发货通知ASN", "收货确认", "发票流转");
    private static final double MANUAL_PER_DOC_MIN = 10.0;   // 人工每单处理分钟
    private static final double LABOR_COST = 12.0;           // 万元/人年
    private static final double ANNUAL_HOURS = 2000.0;       // 年有效工时

    @Override
    public String engineKey() {
        return "edi";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "daily_documents", 50, 5000, errors);
        enumParam(params, "message_standard", STANDARDS, errors);
        doubleParam(params, "manual_error_rate", 0.005, 0.05, errors);
        Double autoError = doubleParam(params, "auto_error_rate", 0.0001, 0.001, errors);
        doubleParam(params, "transfer_delay", 1, 60, errors);
        doubleParam(params, "implementation_cost", 10, 200, errors);
        // 约束 reliability_ok：传输可靠性需大于 99.99%
        if (errors.isEmpty() && autoError != null && autoError > 0.0005) {
            errors.add("reliability_ok 约束不满足：auto_error_rate (" + autoError + ") 必须 ≤ 0.0005");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int dailyDocuments = ((Number) params.get("daily_documents")).intValue();
        String messageStandard = String.valueOf(params.get("message_standard"));
        double manualErrorRate = ((Number) params.get("manual_error_rate")).doubleValue();
        double autoErrorRate = ((Number) params.get("auto_error_rate")).doubleValue();
        double transferDelay = ((Number) params.get("transfer_delay")).doubleValue();
        double implementationCost = ((Number) params.get("implementation_cost")).doubleValue();

        double stdFactor = switch (messageStandard) {
            case "xml" -> 0.95;
            case "json_api" -> 0.90;
            default -> 1.0;
        };
        double ediPerDocMin = (1.0 + transferDelay / 60.0) * stdFactor;

        // 步骤 1：单证流转链与标准转换
        ctx.step(String.format("EDI 单证流转链（%s 标准）：%s；日单证量 %d 份，传输延迟 %.0f s",
                messageStandard, String.join(" → ", STAGES), dailyDocuments, transferDelay),
                Map.of("message_standard", messageStandard, "daily_documents", dailyDocuments));

        // 步骤 2：差错率对比
        double manualErrors = dailyDocuments * 365.0 * manualErrorRate;
        double ediErrors = dailyDocuments * 365.0 * autoErrorRate;
        List<Map<String, Object>> errorItems = new ArrayList<>();
        errorItems.add(Map.of("name", "人工录入", "value", round2(manualErrorRate * 100)));
        errorItems.add(Map.of("name", "EDI自动转换", "value", round2(autoErrorRate * 100)));
        ctx.step(String.format("差错率对比：人工 %.2f%%（年差错 %.0f 份） vs EDI %.4f%%（年差错 %.0f 份）",
                manualErrorRate * 100, manualErrors, autoErrorRate * 100, ediErrors),
                Map.of("manual_errors", round2(manualErrors), "edi_errors", round2(ediErrors)));

        // 步骤 3：端到端流转时间（八环节累计，EDI vs 人工）
        double[] ediStage = {0.5, 0.2, transferDelay / 60.0, 0.2, 0.3, 0.3, 0.2, 0.3};
        double[] manualStage = {2.0, 1.0, 2.0, 0.8, 1.5, 1.5, 0.8, 1.5};
        List<Double> sx = new ArrayList<>();
        List<Double> ediCum = new ArrayList<>();
        List<Double> manualCum = new ArrayList<>();
        double eSum = 0;
        double mSum = 0;
        for (int i = 0; i < STAGES.size(); i++) {
            eSum += ediStage[i];
            mSum += manualStage[i];
            sx.add((double) (i + 1));
            ediCum.add(round2(eSum * stdFactor));
            manualCum.add(round2(mSum));
        }
        Map<String, Object> flowSeries = new LinkedHashMap<>();
        flowSeries.put("x", sx);
        flowSeries.put("series", List.of(
                Map.of("name", "EDI流转(分钟)", "data", ediCum),
                Map.of("name", "人工流转(分钟)", "data", manualCum)));
        double efficiencyGain = (MANUAL_PER_DOC_MIN - ediPerDocMin) / MANUAL_PER_DOC_MIN * 100;
        ctx.step(String.format("端到端流转：EDI %.2f 分钟 vs 人工 %.1f 分钟 → 效率提升 %.1f%%",
                eSum * stdFactor, mSum, efficiencyGain),
                Map.of("edi_flow_min", round2(eSum * stdFactor), "manual_flow_min", round2(mSum)));

        // 步骤 4：ROI 回收期
        double annualSaving = dailyDocuments * 365.0 * (MANUAL_PER_DOC_MIN - ediPerDocMin)
                / 60.0 / ANNUAL_HOURS * LABOR_COST;
        double payback = implementationCost / Math.max(0.01, annualSaving);
        ctx.step(String.format("年节省人力 %.0f 万元（日 %d 份 × 365 天 × 每单省 %.1f 分钟）；"
                        + "实施投入 %.0f 万元 → ROI 回收期 %.2f 年",
                annualSaving, dailyDocuments, MANUAL_PER_DOC_MIN - ediPerDocMin,
                implementationCost, payback),
                Map.of("annual_saving", round2(annualSaving), "roi_payback", round2(payback)));

        // 输出指标（FR-007）
        ctx.output("efficiency_gain", "单证处理效率提升率", "scalar", round2(efficiencyGain), "%");
        ctx.output("error_compare", "差错率对比(EDI vs 人工)", "compare", errorItems, "%");
        ctx.output("flow_time", "端到端单证流转时间", "series", flowSeries, "分钟");
        ctx.output("roi_payback", "ROI回收期", "scalar", round2(payback), "年");
    }
}
