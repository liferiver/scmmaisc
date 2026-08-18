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
 * CPFR 协同规划预测补货仿真执行器（T057，CH6-003）。
 * 模型：52 周历史 → 零售商/制造商独立预测（seed 抽样）→ 差异比对（超阈值 = 异常）→
 * 按协同深度（无协同/信息共享/协同预测/完全CPFR）缩小差异并解决异常 → 12 周渠道库存模拟
 * （补货 = 协同预测×0.9）→ MAPE/缺货率/异常解决效率对比；促销事件使未协同预测失真。
 */
@Component
public class CpfrExecutor implements ScenarioExecutor {

    private static final Set<String> LEVELS =
            Set.of("none", "info_share_only", "collab_forecast", "cpfr_full");
    private static final int WEEKS = 12;

    @Override
    public String engineKey() {
        return "cpfr";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double threshold = doubleParam(params, "diff_threshold", 0.1, 0.3, errors);
        Double infoShare = doubleParam(params, "info_share", 0, 1, errors);
        Boolean promotion = boolParam(params, "promotion", errors);
        String level = enumParam(params, "collaboration_level", LEVELS, errors);
        if (errors.isEmpty() && threshold != null && infoShare != null
                && promotion != null && level != null) {
            // 约束 threshold_ok：差异阈值需 ≥ 10%
            if (threshold < 0.1) {
                errors.add("threshold_ok 约束不满足：预测差异阈值需 ≥ 0.1（超阈值需协商解决）");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 6;
    }

    /** 可选 timeseries 参数解析：非 List 时返回 null（由执行器生成）。 */
    @SuppressWarnings("unchecked")
    private static List<Number> parseSeries(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Number> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 给定协同深度与信息共享度的预测误差收缩系数。 */
    private static double diffFactor(String level, double infoShare) {
        return switch (level) {
            case "info_share_only" -> 1 - 0.25 * infoShare;
            case "collab_forecast" -> 1 - 0.45 * infoShare;
            case "cpfr_full" -> 1 - 0.65 * infoShare;
            default -> 1.0;
        };
    }

    /** 各协同深度下的组合预测（对真实需求的可见度不同）。 */
    private double[] combinedForecast(String level, double[] retailer, double[] manufacturer,
                                     double infoShare) {
        double[] out = new double[WEEKS];
        for (int w = 0; w < WEEKS; w++) {
            out[w] = switch (level) {
                case "none" -> retailer[w];
                case "info_share_only" -> infoShare * retailer[w] + (1 - infoShare) * manufacturer[w];
                default -> 0.5 * retailer[w] + 0.5 * manufacturer[w];
            };
        }
        return out;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double threshold = ((Number) params.get("diff_threshold")).doubleValue();
        double infoShare = ((Number) params.get("info_share")).doubleValue();
        boolean promotion = (Boolean) params.get("promotion");
        String level = String.valueOf(params.get("collaboration_level"));

        // 步骤 1：历史数据与联合商业计划
        List<Number> historical = parseSeries(params, "historical_sales");
        double[] history;
        if (historical != null && historical.size() >= 12) {
            history = new double[historical.size()];
            for (int i = 0; i < historical.size(); i++) {
                history[i] = historical.get(i).doubleValue();
            }
        } else {
            history = new double[52];
            double v = 1000;
            for (int i = 0; i < 52; i++) {
                v = v * 1.005 + (ctx.random().nextDouble() - 0.5) * 120;
                history[i] = Math.max(100, v);
            }
        }
        double base = history[history.length - 1];
        ctx.step(String.format("历史 %d 周销量（基线 %.0f 件/周）%s", history.length, base,
                        promotion ? "，含促销事件（第 8-12 周需求 ×1.5）" : "，无促销事件"),
                Map.of("base_demand", round2(base), "promotion", promotion));

        // 步骤 2-3：双方独立预测与差异比对（促销认知差异产生异常）
        List<Number> retRaw = parseSeries(params, "retailer_forecast");
        List<Number> manRaw = parseSeries(params, "manufacturer_forecast");
        double[] retailer = new double[WEEKS];
        double[] manufacturer = new double[WEEKS];
        double[] actual = new double[WEEKS];
        for (int w = 0; w < WEEKS; w++) {
            double promoFactor = promotion && w >= 7 ? (1.6 - (w - 7) * 0.1) : 1.0;
            actual[w] = base * promoFactor * (1 + (ctx.random().nextDouble() - 0.5) * 0.1);
            boolean retKnowsPromo = promotion && (level.equals("collab_forecast") || level.equals("cpfr_full"));
            retailer[w] = (retRaw != null && retRaw.size() > w) ? retRaw.get(w).doubleValue()
                    : base * (retKnowsPromo && w >= 7 ? promoFactor : 1.0)
                            * (1 + (ctx.random().nextDouble() - 0.5) * 0.12);
            boolean manKnowsPromo = promotion && level.equals("cpfr_full");
            manufacturer[w] = (manRaw != null && manRaw.size() > w) ? manRaw.get(w).doubleValue()
                    : base * (manKnowsPromo && w >= 7 ? promoFactor : 1.0)
                            * (1 + (ctx.random().nextDouble() - 0.5) * 0.08);
        }
        int exceptions = 0;
        for (int w = 0; w < WEEKS; w++) {
            double diff = Math.abs(retailer[w] - manufacturer[w]) / Math.max(1, retailer[w]);
            if (diff * diffFactor(level, infoShare) > threshold) {
                exceptions++;
            }
        }
        ctx.step(String.format("双方独立预测比对：平均差异 %.1f%%，异常周 %d/%d（阈值 %.0f%%）",
                        avgDiff(retailer, manufacturer) * 100, exceptions, WEEKS, threshold * 100),
                Map.of("exception_weeks", exceptions, "diff_threshold", threshold));

        // 步骤 4：协同解决异常 → 组合预测（按协同深度）
        double[] combined = combinedForecast(level, retailer, manufacturer, infoShare);
        ctx.step(String.format("协同深度 %s（信息共享 %.0f%%）：差异收缩系数 %.2f，异常解决后 %d 次/期",
                        level, infoShare * 100, diffFactor(level, infoShare), exceptions),
                Map.of("collaboration_level", level, "info_share", infoShare));

        // 步骤 5：12 周渠道库存模拟（补货 = 组合预测 × 0.9）
        double inv = base * 12;
        List<Double> invCurve = new ArrayList<>();
        int stockouts = 0;
        for (int w = 0; w < WEEKS; w++) {
            inv = Math.max(0, inv + combined[w] * 0.9 - actual[w]);
            invCurve.add(round2(inv));
            if (inv <= 0) {
                stockouts++;
            }
        }
        double stockoutRate = stockouts * 100.0 / WEEKS;
        ctx.step(String.format("渠道库存模拟 12 周：期末库存 %.0f 件，缺货 %d 周 → 缺货率 %.1f%%",
                        invCurve.get(invCurve.size() - 1), stockouts, stockoutRate),
                Map.of("channel_inventory", invCurve, "stockout_rate", round2(stockoutRate)));

        // 步骤 6：四种协同深度绩效对比
        double mape = mapeOf(combined, actual);
        List<Map<String, Object>> cpfrCompare = new ArrayList<>();
        for (String lv : List.of("none", "info_share_only", "collab_forecast", "cpfr_full")) {
            cpfrCompare.add(Map.of("name", switch (lv) {
                        case "none" -> "无协同";
                        case "info_share_only" -> "信息共享";
                        case "collab_forecast" -> "协同预测";
                        default -> "完全CPFR";
                    },
                    "value", round2(mapeOf(combinedForecast(lv, retailer, manufacturer, infoShare), actual))));
        }
        ctx.step(String.format("当前协同（%s）MAPE = %.1f%%；异常解决效率 %.1f 次/期",
                        level, mape, exceptions / (double) WEEKS),
                Map.of("mape", round2(mape), "cpfr_vs_none", cpfrCompare));

        // 输出指标（FR-007）
        ctx.output("mape", "预测准确率MAPE", "scalar", round2(mape), "%");
        ctx.output("channel_inventory", "渠道总库存", "series",
                series(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), "渠道库存(件)", invCurve), "件");
        ctx.output("stockout_rate", "缺货率", "scalar", round2(stockoutRate), "%");
        ctx.output("cpfr_vs_none", "CPFR vs 非协同对比", "compare", cpfrCompare, null);
        ctx.output("exception_efficiency", "异常解决效率", "scalar", round2(exceptions / (double) WEEKS), "次/期");
    }

    private double avgDiff(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]) / Math.max(1, a[i]);
        }
        return sum / a.length;
    }

    private double mapeOf(double[] forecast, double[] actual) {
        double sum = 0;
        for (int i = 0; i < forecast.length; i++) {
            sum += Math.abs(forecast[i] - actual[i]) / actual[i];
        }
        return sum / forecast.length * 100;
    }
}
