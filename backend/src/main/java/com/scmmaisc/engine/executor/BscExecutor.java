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
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * BSC 平衡计分卡供应链绩效仿真执行器（T057，CH6-006）。
 * 模型：供应链战略目标分解为 13 项 KPI（财务4/客户3/流程3/成长3，目标值可配置）→ 按周期仿真
 * 实际达成（成长→流程→客户→财务因果链传导，causal_matrix 链强度 0-1 可配置）→ 四维加权
 * BSC 综合得分趋势 + 战略地图（topo）+ 领先 vs 滞后指标分析。
 */
@Component
public class BscExecutor implements ScenarioExecutor {

    private static final String[] DIMENSIONS = {"财务", "客户", "内部流程", "学习成长"};
    /** 默认 13 项 KPI 目标：财务4/客户3/流程3/成长3。 */
    private static final double[] DEFAULT_TARGETS =
            {15, 12, 1000, 8, 98, 90, 50, 8, 85, 5, 80, 40, 6};

    @Override
    public String engineKey() {
        return "bsc";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double wF = doubleParam(params, "weight_finance", 0, 1, errors);
        Double wC = doubleParam(params, "weight_customer", 0, 1, errors);
        Double wP = doubleParam(params, "weight_process", 0, 1, errors);
        Double wG = doubleParam(params, "weight_growth", 0, 1, errors);
        Integer periods = intParam(params, "periods", 6, 12, errors);
        if (errors.isEmpty() && wF != null && wC != null && wP != null && wG != null && periods != null) {
            // 约束 weight_sum：BSC 四维权重之和 = 1
            if (Math.abs(wF + wC + wP + wG - 1.0) > 0.01) {
                errors.add("weight_sum 约束不满足：BSC 四维权重之和需等于 1（当前 "
                        + round2(wF + wC + wP + wG) + "）");
            }
            // 约束 dimension_ok：考核周期数需 ≥ 6 期
            if (periods < 6) {
                errors.add("dimension_ok 约束不满足：考核周期数需 ≥ 6 期");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 可选 timeseries 参数解析：非 List 时返回 null。 */
    @SuppressWarnings("unchecked")
    private static double[] parseSeries(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Number n)) {
                return null;
            }
            out[i] = n.doubleValue();
        }
        return out;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double wF = ((Number) params.get("weight_finance")).doubleValue();
        double wC = ((Number) params.get("weight_customer")).doubleValue();
        double wP = ((Number) params.get("weight_process")).doubleValue();
        double wG = ((Number) params.get("weight_growth")).doubleValue();
        int periods = ((Number) params.get("periods")).intValue();

        // 步骤 1-2：战略目标分解与 KPI 目标设定
        double[] targets = parseSeries(params, "kpi_targets");
        if (targets == null || targets.length != 13) {
            targets = DEFAULT_TARGETS;
        }
        // 因果链强度：学习→流程 / 流程→客户 / 客户→财务（默认 0.5）
        double[] chain = {0.5, 0.5, 0.5};
        Object raw = params.get("causal_matrix");
        if (raw instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof List<?> row0
                && !row0.isEmpty()) {
            List<Double> strengths = new ArrayList<>();
            for (Object r : rows) {
                if (r instanceof List<?> row && !row.isEmpty() && row.get(0) instanceof Number n) {
                    strengths.add(Math.min(1, Math.max(0, n.doubleValue())));
                }
            }
            for (int i = 0; i < Math.min(3, strengths.size()); i++) {
                chain[i] = strengths.get(i);
            }
        }
        ctx.step(String.format("战略目标分解：13 项 KPI（财务4/客户3/流程3/成长3，目标：%s…），四维权重 %.0f/%.0f/%.0f/%.0f",
                        targets[0], wF * 100, wC * 100, wP * 100, wG * 100),
                Map.of("weights", List.of(wF, wC, wP, wG), "chain_strengths", List.of(chain[0], chain[1], chain[2])));

        // 步骤 3：周期数据采集（成长→流程→客户→财务因果传导）
        double[] growth = new double[periods];
        double[] process = new double[periods];
        double[] customer = new double[periods];
        double[] finance = new double[periods];
        List<Double> trend = new ArrayList<>();
        for (int t = 0; t < periods; t++) {
            double progress = (t + 1) / (double) periods;
            double noise = (ctx.random().nextDouble() - 0.5) * 6;
            growth[t] = Math.min(115, 62 + progress * 38 + noise);
            process[t] = Math.min(115, 64 + progress * 30
                    + chain[0] * (growth[t] - 70) * 0.4 + noise * 0.6);
            customer[t] = Math.min(115, 68 + progress * 24
                    + chain[1] * (process[t] - 70) * 0.4 + noise * 0.5);
            finance[t] = Math.min(115, 62 + progress * 20
                    + chain[2] * (customer[t] - 70) * 0.4 + noise * 0.4);
            double total = wF * finance[t] + wC * customer[t] + wP * process[t] + wG * growth[t];
            trend.add(round2(total));
        }
        ctx.step(String.format("采集 %d 期数据（因果链：学习→流程 %.1f→客户 %.1f→财务 %.1f）",
                        periods, chain[0], chain[1], chain[2]),
                Map.of("periods", periods, "bsc_trend", trend));

        // 步骤 4：BSC 综合得分与四维达成率
        double gLast = growth[periods - 1], pLast = process[periods - 1];
        double cLast = customer[periods - 1], fLast = finance[periods - 1];
        List<Map<String, Object>> dashboard = List.of(
                Map.of("name", "财务", "value", round2(fLast)),
                Map.of("name", "客户", "value", round2(cLast)),
                Map.of("name", "内部流程", "value", round2(pLast)),
                Map.of("name", "学习成长", "value", round2(gLast)));
        ctx.step(String.format("期末 BSC 综合得分 %.1f（财务 %.1f / 客户 %.1f / 流程 %.1f / 成长 %.1f）",
                        trend.get(trend.size() - 1), fLast, cLast, pLast, gLast),
                Map.of("dimension_dashboard", dashboard, "final_score", round2(trend.get(trend.size() - 1))));

        // 步骤 5：战略地图（topo）与领先/滞后指标分析
        Map<String, Object> topo = new LinkedHashMap<>();
        topo.put("nodes", List.of(
                Map.of("id", "growth", "name", "学习成长(领先)", "type", "dimension"),
                Map.of("id", "process", "name", "内部流程(中游)", "type", "dimension"),
                Map.of("id", "customer", "name", "客户(中游)", "type", "dimension"),
                Map.of("id", "finance", "name", "财务(滞后)", "type", "dimension")));
        topo.put("edges", List.of(
                Map.of("source", "growth", "target", "process"),
                Map.of("source", "process", "target", "customer"),
                Map.of("source", "customer", "target", "finance")));
        List<Map<String, Object>> leadLag = List.of(
                Map.of("name", "领先指标·学习成长", "value", round2(gLast)),
                Map.of("name", "中游·内部流程", "value", round2(pLast)),
                Map.of("name", "中游·客户", "value", round2(cLast)),
                Map.of("name", "滞后指标·财务", "value", round2(fLast)));
        ctx.step("战略地图：领先(成长)→中游(流程/客户)→滞后(财务) 因果传导链分析完成",
                Map.of("strategy_map", topo, "lead_lag", leadLag));

        // 输出指标（FR-007）
        ctx.output("bsc_trend", "BSC综合得分趋势", "series",
                series(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).subList(0, periods),
                        "综合得分", trend), null);
        ctx.output("dimension_dashboard", "四维达成率仪表盘", "gauge", dashboard, "%");
        ctx.output("strategy_map", "战略地图因果链", "topo", topo, null);
        ctx.output("lead_lag", "领先vs滞后指标分析", "compare", leadLag, null);
    }
}
