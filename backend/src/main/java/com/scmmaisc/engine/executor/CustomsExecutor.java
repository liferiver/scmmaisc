package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.boolParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 报关-报检-通关流程仿真执行器（T056，CH5-003）。
 * 模型：HS 归类 → 申报 → 电子/人工审单 → 查验（随机+布控，AEO 降低）→ 缴税 → 放行 → 提货，
 * 逐节点累计通关耗时；低报价格降低申报关税但抬高合规风险评分与退单率（道德困境）。
 * 随机模型：查验/退单抽样依赖 seed（FR-008 可复现）。
 */
@Component
public class CustomsExecutor implements ScenarioExecutor {

    private static final Set<String> GOODS_TYPES =
            Set.of("general", "food", "cosmetics", "electronics", "machinery");
    private static final Set<String> DECL_VALUES =
            Set.of("compliance", "low_report_10", "low_report_30");
    /** 敏感商品：需检验检疫，人工审单与查验加时。 */
    private static final Set<String> SENSITIVE = Set.of("food", "cosmetics");

    /** 单票货值（元）：普通/食品/化妆品/电子产品/机械。 */
    private static final Map<String, Double> GOODS_VALUE = Map.of(
            "general", 10_000.0, "food", 12_000.0, "cosmetics", 15_000.0,
            "electronics", 20_000.0, "machinery", 30_000.0);
    /** 基准关税税率：普通/食品/化妆品/电子产品/机械。 */
    private static final Map<String, Double> TARIFF_RATE = Map.of(
            "general", 0.10, "food", 0.13, "cosmetics", 0.20,
            "electronics", 0.08, "machinery", 0.06);

    @Override
    public String engineKey() {
        return "customs";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        String goods = enumParam(params, "goods_type", GOODS_TYPES, errors);
        Double accuracy = doubleParam(params, "hs_accuracy", 0.8, 1.0, errors);
        String decl = enumParam(params, "declaration_value", DECL_VALUES, errors);
        Boolean aeo = boolParam(params, "aeo_certified", errors);
        Double doc = doubleParam(params, "doc_completeness", 0.7, 1.0, errors);
        Double randomInspect = doubleParam(params, "random_inspect_rate", 0.01, 0.05, errors);
        Double targeted = doubleParam(params, "targeted_inspect_rate", 0, 0.3, errors);
        if (errors.isEmpty() && goods != null && accuracy != null && decl != null && aeo != null
                && doc != null && randomInspect != null && targeted != null) {
            // 约束 compliance_ok：低报 30% 触发高额罚款风险，需保持合规
            if ("low_report_30".equals(decl)) {
                errors.add("compliance_ok 约束不满足：需保持合规（低报 30% 将触发补税+罚款风险，退出合规通道）");
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
        String goods = String.valueOf(params.get("goods_type"));
        double accuracy = ((Number) params.get("hs_accuracy")).doubleValue();
        String decl = String.valueOf(params.get("declaration_value"));
        boolean aeo = (Boolean) params.get("aeo_certified");
        double doc = ((Number) params.get("doc_completeness")).doubleValue();
        double randomInspect = ((Number) params.get("random_inspect_rate")).doubleValue();
        double targeted = ((Number) params.get("targeted_inspect_rate")).doubleValue();

        double value = GOODS_VALUE.get(goods);
        double rate = TARIFF_RATE.get(goods);
        boolean sensitive = SENSITIVE.contains(goods);
        double declaredFactor = switch (decl) {
            case "low_report_10" -> 0.9;
            case "low_report_30" -> 0.7;
            default -> 1.0;
        };

        // 步骤 1：HS 归类与申报要素
        double effectiveRandom = aeo ? randomInspect * 0.4 : randomInspect;
        double inspectProb = Math.min(1.0, effectiveRandom + targeted
                + ("low_report_10".equals(decl) ? 0.05 : 0)
                + ("low_report_30".equals(decl) ? 0.15 : 0));
        boolean inspected = ctx.random().nextDouble() < inspectProb;
        double rejectProb = Math.min(1.0, (1 - accuracy) * 0.5 + (1 - doc) * 0.3
                + ("low_report_30".equals(decl) ? 0.05 : 0));
        boolean rejected = ctx.random().nextDouble() < rejectProb;
        ctx.step(String.format("%s 单票货值 %.0f 元，HS 准确率 %.0f%%，申报要素完整度 %.0f%%（%s）",
                        goods, value, accuracy * 100, doc * 100, sensitive ? "敏感商品需检验检疫" : "普通商品"),
                Map.of("declared_value_factor", declaredFactor, "hs_accuracy", round2(accuracy)));

        // 步骤 2-4：电子审单 → 人工审单 → 查验（累计通关耗时，天）
        List<Double> cumDays = new ArrayList<>();
        double t = 0.10; // 申报录入
        cumDays.add(t);
        t += 0.15; // 电子审单
        cumDays.add(t);
        boolean manualReview = accuracy < 0.95 || sensitive;
        t += manualReview ? 0.25 : 0.05; // 人工审单（仅存疑单）
        cumDays.add(t);
        if (rejected) {
            ctx.step(String.format("退单：资料不全/归类错误（退单率 %.1f%%），通关终止于 %.2f 天", rejectProb * 100, t),
                    Map.of("rejected", true, "reject_reason",
                            accuracy < 0.95 ? "HS 归类错误" : "申报资料不全"));
        } else {
            t += inspected ? (sensitive ? 0.8 : 0.5) : 0.0; // 查验
            cumDays.add(t);
            t += 0.10; // 缴税
            cumDays.add(t);
            t += 0.05; // 放行
            cumDays.add(t);
            t += 0.10; // 提货
            cumDays.add(t);
            double total = cumDays.get(cumDays.size() - 1);
            ctx.step(String.format("审单→查验：%s，查验耗时 %s，通关总耗时 %.2f 天",
                            manualReview ? "人工审单" : "电子审单直放",
                            inspected ? (sensitive ? "0.8 天（检验检疫）" : "0.5 天") : "未查验 0 天",
                            total),
                    Map.of("manual_review", manualReview, "inspected", inspected,
                            "clearance_days", round2(total)));
        }

        // 步骤 5：关税与合规风险
        double tariff = value * rate * declaredFactor;
        List<Double> stageX = new ArrayList<>();
        for (int i = 0; i < cumDays.size(); i++) {
            stageX.add(round2(cumDays.get(i)));
        }
        double risk = Math.min(100, 12 + (1 - doc) * 35 + (1 - accuracy) * 30
                + ("low_report_10".equals(decl) ? 25 : 0)
                + ("low_report_30".equals(decl) ? 45 : 0)
                + targeted * 40 - (aeo ? 8 : 0));
        double fine = "low_report_30".equals(decl) ? value * 0.3 * 2 : 0.0; // 补税+罚款
        ctx.step(String.format("申报关税 %.0f 元（申报价值系数 %.0f%%），合规风险 %.0f 分%s",
                        tariff, declaredFactor * 100, risk, fine > 0 ? "，低报 30% 潜在罚款 " + (long) fine + " 元" : ""),
                Map.of("tariff_total", round2(tariff), "risk_score", round2(risk), "potential_fine", round2(fine)));

        // 输出指标（FR-007）
        ctx.output("clearance_time", "通关总耗时", "series",
                series(List.of(1, 2, 3, 4, 5, 6, 7).subList(0, cumDays.size()), "通关累计耗时(天)", stageX), "天");
        ctx.output("inspect_rate", "查验率", "scalar", round2(inspectProb * 100), "%");
        ctx.output("tariff_total", "关税总额", "scalar", round2(tariff), "元");
        ctx.output("risk_score", "合规风险评分", "gauge", round2(risk), null);
        ctx.output("reject_rate", "退单率", "scalar", round2(rejectProb * 100), "%");
    }
}
