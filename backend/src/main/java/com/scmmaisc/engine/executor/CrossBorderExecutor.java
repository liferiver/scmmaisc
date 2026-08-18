package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.distParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 跨境物流“境内-跨境-境外”三段式运输仿真执行器（T028，CH5-001）。
 * 模型：境内段（国内运输 + 出口报关，正态抽样）、跨境段（按运输方式/贸易通道取标准时效）、
 * 境外段（进口清关抽样 + 境外运输）；海关查验概率注入延误；关税按商品类别差异化。
 * 随机模型：清关时长抽样依赖 seed（FR-008 可复现）。
 */
@Component
public class CrossBorderExecutor implements ScenarioExecutor {

    private static final Set<String> CHANNELS = Set.of("cn_us", "cn_eu", "cn_asean", "cn_jpkr");
    private static final Set<String> TRANSPORT_MODES = Set.of("sea", "air", "rail", "road", "multimodal");
    private static final Set<String> CATEGORIES = Set.of("general", "cross_ecommerce", "personal");

    @Override
    public String engineKey() {
        return "cross-border";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        String channel = enumParam(params, "trade_channel", CHANNELS, errors);
        String mode = enumParam(params, "transport_mode", TRANSPORT_MODES, errors);
        Map<String, Double> exportCl = params.containsKey("export_clearance_time")
                ? distParam(params, "export_clearance_time",
                        Map.of("mean", new double[]{0.5, 7}, "sd", new double[]{0.1, 2}), errors)
                : null; // 可选分布：缺省时由 run() 使用内置清关参数
        Map<String, Double> importCl = params.containsKey("import_clearance_time")
                ? distParam(params, "import_clearance_time",
                        Map.of("mean", new double[]{1, 14}, "sd", new double[]{0.2, 3}), errors)
                : null; // 可选分布：缺省时由 run() 使用内置清关参数
        Double inspection = doubleParam(params, "inspection_probability", 0.01, 0.10, errors);
        Double tariff = doubleParam(params, "tariff_rate", 0, 0.25, errors);
        String category = enumParam(params, "goods_category", CATEGORIES, errors);
        if (errors.isEmpty() && channel != null && mode != null && exportCl != null
                && importCl != null && inspection != null && tariff != null && category != null) {
            // 约束 compliance：个人物品仅适用空运/海运（合规申报通道）
            if ("personal".equals(category) && ("rail".equals(mode) || "road".equals(mode))) {
                errors.add("compliance 约束不满足：个人物品类商品不支持铁路/公路运输，请改用海运或空运");
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
        String channel = String.valueOf(params.get("trade_channel"));
        String mode = String.valueOf(params.get("transport_mode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> exportCl = (Map<String, Object>) params.get("export_clearance_time");
        if (exportCl == null) {
            exportCl = Map.of("mean", 1.0, "sd", 0.2); // 缺省：出口清关 1±0.2 天
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> importCl = (Map<String, Object>) params.get("import_clearance_time");
        if (importCl == null) {
            importCl = Map.of("mean", 2.0, "sd", 0.5); // 缺省：进口清关 2±0.5 天
        }
        double inspection = ((Number) params.get("inspection_probability")).doubleValue();
        double tariff = ((Number) params.get("tariff_rate")).doubleValue();
        String category = String.valueOf(params.get("goods_category"));

        // 跨境段基准时效（天）：运输方式 × 贸易通道修正
        double baseTransit = switch (mode) {
            case "sea" -> 25.0;
            case "air" -> 3.0;
            case "rail" -> 16.0;
            case "road" -> 8.0;
            default -> 12.0; // multimodal
        };
        double channelFactor = switch (channel) {
            case "cn_asean" -> 0.55;  // 东南亚近岸
            case "cn_jpkr" -> 0.45;   // 日韩最近
            case "cn_eu" -> 1.05;
            default -> 1.0;           // cn_us
        };
        double transit = baseTransit * channelFactor;

        // 境内段：国内运输 2 天 + 出口报关抽样（查验 +1 天）
        double exportMean = ((Number) exportCl.get("mean")).doubleValue();
        double exportSd = ((Number) exportCl.get("sd")).doubleValue();
        double exportDays = Math.max(0.5, exportMean + ctx.random().nextGaussian() * exportSd);
        double inspected = ctx.random().nextDouble() < inspection ? 1.0 : 0.0;

        // 境外段：进口清关抽样 + 境外运输 2 天
        double importMean = ((Number) importCl.get("mean")).doubleValue();
        double importSd = ((Number) importCl.get("sd")).doubleValue();
        double importDays = Math.max(0.5, importMean + ctx.random().nextGaussian() * importSd);

        double domestic = 2 + exportDays + inspected;
        double overseas = importDays + 2;
        double totalDays = domestic + transit + overseas;

        // 步骤 1：境内段
        ctx.step(String.format("境内段：国内运输 2 天 + 出口报关 %.1f 天（抽样，%s）→ %.1f 天",
                exportDays, inspected > 0 ? "触发查验 +1 天" : "未查验", domestic),
                Map.of("domestic_days", round2(domestic)));

        // 步骤 2：跨境段
        ctx.step(String.format("跨境段：%s × %s → 国际运输 %.1f 天",
                channel, mode, transit), Map.of("transit_days", round2(transit)));

        // 步骤 3：境外段
        ctx.step(String.format("境外段：进口清关 %.1f 天（抽样）+ 境外运输 2 天 → %.1f 天",
                importDays, overseas), Map.of("overseas_days", round2(overseas)));

        // 步骤 4：全程节点拓扑与时效占比
        List<Integer> segX = List.of(1, 2, 3);
        Map<String, Object> segmentSeries = series(segX, "耗时(天)",
                List.of(round2(domestic), round2(transit), round2(overseas)));
        Map<String, Object> topo = new LinkedHashMap<>();
        topo.put("nodes", List.of(
                Map.of("id", "factory", "name", "境内工厂", "type", "origin"),
                Map.of("id", "export", "name", "出口报关", "type", "clearance"),
                Map.of("id", "border", "name", "出境口岸", "type", "port"),
                Map.of("id", "transit", "name", "国际运输", "type", "transport"),
                Map.of("id", "port2", "name", "目的国口岸", "type", "port"),
                Map.of("id", "import", "name", "进口清关", "type", "clearance"),
                Map.of("id", "delivery", "name", "境外配送", "type", "delivery")));
        topo.put("edges", List.of(
                Map.of("source", "factory", "target", "export"),
                Map.of("source", "export", "target", "border"),
                Map.of("source", "border", "target", "transit"),
                Map.of("source", "transit", "target", "port2"),
                Map.of("source", "port2", "target", "import"),
                Map.of("source", "import", "target", "delivery")));
        ctx.step(String.format("全程合计 %.1f 天：境内 %.0f%%、跨境 %.0f%%、境外 %.0f%%",
                totalDays, domestic / totalDays * 100, transit / totalDays * 100, overseas / totalDays * 100),
                Map.of("total_days", round2(totalDays), "segment_times", segmentSeries));

        // 步骤 5：成本与延误风险
        double modeCost = switch (mode) {
            case "sea" -> 8_000.0;
            case "air" -> 80_000.0;
            case "rail" -> 30_000.0;
            case "road" -> 20_000.0;
            default -> 35_000.0;
        };
        double categoryFactor = switch (category) {
            case "cross_ecommerce" -> 0.7;   // 跨境电商综合税优惠
            case "personal" -> 0.2;           // 行邮税
            default -> 1.0;                   // 一般贸易
        };
        double goodsValue = 100_000.0;
        double tariffCost = goodsValue * tariff * categoryFactor;
        double totalCost = modeCost + tariffCost;
        double delayRisk = inspection + 0.05 + switch (mode) {
            case "sea" -> 0.15;
            case "air" -> 0.05;
            case "rail" -> 0.10;
            case "road" -> 0.20;
            default -> 0.12;
        };
        ctx.step(String.format("全程物流成本 %.0f 元（运输 %.0f + 关税 %.0f），延误风险概率 %.0f%%",
                totalCost, modeCost, tariffCost, delayRisk * 100),
                Map.of("total_cost", round2(totalCost), "delay_risk", round2(delayRisk * 100)));

        // 输出指标（FR-007）
        ctx.output("segment_times", "三段总耗时", "series", segmentSeries, "天");
        ctx.output("segment_share", "各段耗时占比", "gauge",
                List.of(Map.of("name", "境内段", "value", round2(domestic / totalDays * 100)),
                        Map.of("name", "跨境段", "value", round2(transit / totalDays * 100)),
                        Map.of("name", "境外段", "value", round2(overseas / totalDays * 100))),
                "%");
        ctx.output("journey_nodes", "全程可视化节点", "topo", topo, null);
        ctx.output("delay_risk", "延误风险概率", "scalar", round2(delayRisk * 100), "%");
        ctx.output("total_cost", "全程物流成本", "scalar", round2(totalCost), "元");
    }
}
