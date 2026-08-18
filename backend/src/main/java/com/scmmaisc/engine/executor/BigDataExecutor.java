package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 大数据驱动的供应链动态决策仿真执行器（T062，CH11-003）。
 * 模型（轻量启发式）：365 天逐日种子仿真——需求冲击事件（促销/舆情/天气）随机发生；传统经验
 * 决策受周报滞后（legacy_delay 天）拖累，冲击当日无法响应；大数据决策依托 POS 分钟级刷新
 * （pos_refresh_minutes）当日捕捉冲击；社交媒体信号按信噪比（social_signal_ratio）命中真实
 * 事件、按误报率（false_positive_rate）产生虚假预警引发错误补货 → 利润/缺货率/周转对比，
 * 强调"信号验证"：大数据也含噪音，需交叉验证后再决策。
 */
@Component
public class BigDataExecutor implements ScenarioExecutor {

    private static final double BASE_UNITS = 10000.0;    // 日需求基数（件）
    private static final double UNIT_MARGIN = 5.0;       // 单件毛利（元）
    private static final double SHOCK_PROB = 0.12;       // 需求冲击日概率
    private static final double SIGNAL_PROB = 0.10;      // 社媒信号触发概率
    private static final double FALSE_ALARM_COST = 8000.0; // 每次误报补货损失（元）
    private static final double INFRA_COST = 500000.0;   // 大数据平台年成本（元）

    @Override
    public String engineKey() {
        return "big-data";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "data_source_count", 3, 6, errors);
        doubleParam(params, "pos_refresh_minutes", 15, 60, errors);
        doubleParam(params, "social_signal_ratio", 0.1, 0.5, errors);
        doubleParam(params, "legacy_delay", 1, 7, errors);
        doubleParam(params, "bigdata_delay", 15, 60, errors);
        doubleParam(params, "false_positive_rate", 0.05, 0.25, errors);
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int sources = ((Number) params.get("data_source_count")).intValue();
        double posRefresh = ((Number) params.get("pos_refresh_minutes")).doubleValue();
        double socialRatio = ((Number) params.get("social_signal_ratio")).doubleValue();
        double legacyDelay = ((Number) params.get("legacy_delay")).doubleValue();
        double bigdataDelay = ((Number) params.get("bigdata_delay")).doubleValue();
        double falseRate = ((Number) params.get("false_positive_rate")).doubleValue();

        // 步骤 1：多源数据接入配置（POS/社媒/天气/交通）
        String[] sourcesDesc = {"POS实时销售", "社交媒体情绪", "天气预报", "交通路况"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources; i++) {
            sb.append(sourcesDesc[i]).append(i < sources - 1 ? "、" : "");
        }
        ctx.step(String.format("接入 %d 个数据源：%s；POS 刷新频率 %.0f 分钟，社媒信号信噪比 %.0f%%",
                sources, sb, posRefresh, socialRatio * 100),
                Map.of("data_source_count", sources, "pos_refresh_minutes", round2(posRefresh),
                        "social_signal_ratio", round2(socialRatio)));

        // 步骤 2：决策延迟对比（传统周报 vs 大数据实时）
        double responseSpeedup = (legacyDelay * 24 - bigdataDelay) / (legacyDelay * 24) * 100;
        ctx.step(String.format("决策延迟：传统 %.1f 天（周报汇总+人工分析）vs 大数据 %.0f 分钟（实时流处理）"
                        + "→ 响应速度提升 %.1f%%", legacyDelay, bigdataDelay, responseSpeedup),
                Map.of("legacy_delay_days", round2(legacyDelay), "bigdata_delay_min", round2(bigdataDelay),
                        "response_speedup", round2(responseSpeedup)));

        // 步骤 3：365 天逐日仿真（需求冲击 + 两类决策的缺货响应）
        int shockDays = 0, legacyStockout = 0, bigdataStockout = 0;
        for (int d = 1; d <= 365; d++) {
            boolean shock = ctx.random().nextDouble() < SHOCK_PROB;
            if (shock) {
                shockDays++;
                // 传统：周报滞后 legacy_delay 天 → 冲击当日大概率缺货
                if (ctx.random().nextDouble() < Math.min(1.0, legacyDelay / 7.0)) {
                    legacyStockout++;
                }
                // 大数据：POS 分钟级感知 → 仅刷新过慢时漏接
                if (ctx.random().nextDouble() < posRefresh / 60.0 * 0.2) {
                    bigdataStockout++;
                }
            }
        }
        double legacyLoss = legacyStockout * BASE_UNITS * UNIT_MARGIN * 0.6;
        double bigdataLoss = bigdataStockout * BASE_UNITS * UNIT_MARGIN * 0.6;
        ctx.step(String.format("仿真 %d 天：需求冲击 %d 天（%.1f%%）；传统决策缺货 %d 天（损失 %,.0f 元），"
                        + "大数据决策缺货 %d 天（损失 %,.0f 元）",
                365, shockDays, shockDays * 100.0 / 365, legacyStockout, legacyLoss, bigdataStockout, bigdataLoss),
                Map.of("shock_days", shockDays, "legacy_stockout_days", legacyStockout,
                        "bigdata_stockout_days", bigdataStockout));

        // 步骤 4：社交媒体信号验证（真实命中 vs 误报 → 错误补货）
        int trueSignals = 0, falseAlarms = 0;
        for (int d = 1; d <= 365; d++) {
            if (ctx.random().nextDouble() < SIGNAL_PROB) {
                if (ctx.random().nextDouble() < socialRatio) {
                    trueSignals++;
                } else {
                    falseAlarms++;
                }
            }
        }
        double falseAlarmLoss = falseAlarms * FALSE_ALARM_COST;
        ctx.step(String.format("社媒信号 %d 次：真实命中 %.0f%%（%d 次，支撑提前补货），误报 %d 次"
                        + "（误报率 %.0f%%→错误补货损失 %,.0f 元）——大数据同样有噪音，需信号验证",
                trueSignals + falseAlarms, socialRatio * 100, trueSignals, falseAlarms,
                falseRate * 100, falseAlarmLoss),
                Map.of("true_signals", trueSignals, "false_alarms", falseAlarms,
                        "false_alarm_loss", round2(falseAlarmLoss)));

        // 步骤 5：利润与运营指标对比
        double legacyProfit = 365 * BASE_UNITS * UNIT_MARGIN - legacyLoss;
        double bigdataProfit = 365 * BASE_UNITS * UNIT_MARGIN - bigdataLoss - falseAlarmLoss - INFRA_COST;
        List<Map<String, Object>> profitCompare = List.of(
                Map.of("name", "传统经验决策", "value", round2(legacyProfit)),
                Map.of("name", "大数据决策", "value", round2(bigdataProfit)));
        double turnoverImprovement = sources * 0.6 + (60 - posRefresh) / 60.0 * 2 + 0.5;
        List<Map<String, Object>> stockoutCompare = List.of(
                Map.of("name", "传统决策", "value", round2(legacyStockout * 100.0 / 365)),
                Map.of("name", "大数据决策", "value", round2(bigdataStockout * 100.0 / 365)));
        String verdict = bigdataProfit > legacyProfit
                ? "数据驱动的收益（缺货损失大幅下降）超过数据平台成本与误报损失，但误报率每提升 5 个百分点约侵蚀利润 "
                + String.format("%,.0f 元，信号交叉验证是落地前提", 365 * SIGNAL_PROB * 0.05 * FALSE_ALARM_COST)
                : "数据平台投入/误报成本过高，需提高信噪比或降低误报率后再规模化";
        ctx.step(String.format("年利润：传统 %,.0f 元 vs 大数据 %,.0f 元（+%,.0f 元）；"
                        + "缺货率 %.2f%%→%.2f%%，库存周转提升 %.1f%%。结论：%s",
                legacyProfit, bigdataProfit, bigdataProfit - legacyProfit,
                legacyStockout * 100.0 / 365, bigdataStockout * 100.0 / 365, turnoverImprovement, verdict),
                Map.of("profit_compare", profitCompare, "stockout_compare", stockoutCompare,
                        "turnover_improvement", round2(turnoverImprovement)));

        // 输出指标（FR-007）
        ctx.output("profit_compare", "大数据vs传统决策利润对比", "compare", profitCompare, "元");
        ctx.output("response_speedup", "响应速度提升", "scalar", round2(responseSpeedup), "%");
        ctx.output("false_alarm_loss", "误报导致损失", "scalar", round2(falseAlarmLoss), "元");
        ctx.output("turnover_improvement", "库存周转改善", "scalar", round2(turnoverImprovement), "%");
        ctx.output("stockout_compare", "缺货率对比", "compare", stockoutCompare, "%");
    }
}
