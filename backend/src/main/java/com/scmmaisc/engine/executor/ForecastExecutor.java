package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 时间序列需求预测方法对比仿真执行器（T030，CH11-001）。
 * 模型：按趋势+季节+噪声生成需求序列，训练/测试划分后对比简单移动平均、单指数平滑、
 * 双指数平滑(Holt)、三指数平滑(Holt-Winters) 的 MAD/MSE/MAPE，推荐最优方法。
 * 确定性模型：噪声用固定种子 42 生成，seed 无关（FR-008），validate 可复算 MAPE 执行约束。
 */
@Component
public class ForecastExecutor implements ScenarioExecutor {

    private static final Set<String> METHODS = Set.of("all", "ma", "ses", "holt", "holt_winters");
    private static final String[] METHOD_KEYS = {"ma", "ses", "holt", "holt_winters"};
    private static final String[] METHOD_NAMES = {"简单移动平均", "单指数平滑", "双指数平滑(Holt)", "三指数平滑(Holt-Winters)"};

    @Override
    public String engineKey() {
        return "forecast";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer periods = intParam(params, "periods", 36, 120, errors);
        Double trend = doubleParam(params, "trend_strength", 0, 0.1, errors);
        Integer seasonPeriod = intParam(params, "season_period", 4, 12, errors);
        Double seasonAmp = doubleParam(params, "season_amplitude", 0.1, 0.5, errors);
        Double noise = doubleParam(params, "noise_level", 0.05, 0.3, errors);
        Double trainRatio = doubleParam(params, "train_ratio", 0.5, 0.9, errors);
        String method = enumParam(params, "forecast_method", METHODS, errors);
        if (errors.isEmpty() && periods != null && trend != null && seasonPeriod != null
                && seasonAmp != null && noise != null && trainRatio != null && method != null) {
            double[] demand = generate(periods, trend, seasonPeriod, seasonAmp, noise);
            int train = (int) Math.round(periods * trainRatio);
            double bestMape = Double.MAX_VALUE;
            for (int i = 0; i < METHOD_KEYS.length; i++) {
                if (selected(method, METHOD_KEYS[i])) {
                    double mape = mape(demand, forecast(METHOD_KEYS[i], demand, train, seasonPeriod), train);
                    bestMape = Math.min(bestMape, mape);
                }
            }
            // 约束 mape_usable：最优方法 MAPE 须 < 20%
            if (bestMape >= 0.2) {
                errors.add(String.format("mape_usable 约束不满足：所选方法最优 MAPE %.1f%% 需低于 20%% 方为可用（噪声过大）",
                        bestMape * 100));
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
        int periods = ((Number) params.get("periods")).intValue();
        double trend = ((Number) params.get("trend_strength")).doubleValue();
        int seasonPeriod = ((Number) params.get("season_period")).intValue();
        double seasonAmp = ((Number) params.get("season_amplitude")).doubleValue();
        double noise = ((Number) params.get("noise_level")).doubleValue();
        double trainRatio = ((Number) params.get("train_ratio")).doubleValue();
        String method = String.valueOf(params.get("forecast_method"));

        // 步骤 1：历史需求序列生成
        double[] demand = generate(periods, trend, seasonPeriod, seasonAmp, noise);
        ctx.step(String.format("需求序列生成：%d 期，趋势 %.2f%%/期，季节周期 %d 期（振幅 %.0f%%），噪声 σ=%.0f%%",
                periods, trend * 100, seasonPeriod, seasonAmp * 100, noise * 100),
                Map.of("periods", periods, "noise_level", round2(noise)));

        // 步骤 2：训练/测试划分
        int train = (int) Math.round(periods * trainRatio);
        int test = periods - train;
        ctx.step(String.format("数据集划分：训练 %d 期（%.0f%%），测试 %d 期（%.0f%%）",
                train, trainRatio * 100, test, (1 - trainRatio) * 100),
                Map.of("train_size", train, "test_size", test));

        // 步骤 3：各方法预测
        double[][] forecasts = new double[METHOD_KEYS.length][];
        double[] mapes = new double[METHOD_KEYS.length];
        for (int i = 0; i < METHOD_KEYS.length; i++) {
            forecasts[i] = forecast(METHOD_KEYS[i], demand, train, seasonPeriod);
            mapes[i] = mape(demand, forecasts[i], train);
            double mad = mad(demand, forecasts[i], train);
            double mse = mse(demand, forecasts[i], train);
            ctx.step(String.format("%s：MAD %.0f，MSE %.0f，MAPE %.1f%%",
                    METHOD_NAMES[i], mad, mse, mapes[i] * 100),
                    Map.of("method", METHOD_KEYS[i], "mape", round2(mapes[i] * 100)));
        }

        // 步骤 4：误差对比与最优方法
        int bestIdx = 0;
        double bestMape = Double.MAX_VALUE;
        for (int i = 0; i < METHOD_KEYS.length; i++) {
            if (!selected(method, METHOD_KEYS[i])) {
                mapes[i] = Double.MAX_VALUE;
            } else if (mapes[i] < bestMape) {
                bestMape = mapes[i];
                bestIdx = i;
            }
        }
        ctx.step(String.format("误差对比：%s 最优（MAPE %.1f%%）%s",
                METHOD_NAMES[bestIdx], mapes[bestIdx] * 100,
                mapes[bestIdx] < 0.2 ? "，精度可用" : "，MAPE 超过 20% 建议改善数据质量"),
                Map.of("best_method", METHOD_NAMES[bestIdx], "best_mape", round2(mapes[bestIdx] * 100)));

        // 步骤 5：预测区间与结论
        double sigma = noise * mean(demand, 0, train);
        List<Number> xInt = new ArrayList<>();
        List<Number> upper = new ArrayList<>();
        List<Number> lower = new ArrayList<>();
        for (int t = train; t < periods; t++) {
            xInt.add(t + 1);
            upper.add(round2(forecasts[bestIdx][t] + 1.96 * sigma));
            lower.add(round2(Math.max(0, forecasts[bestIdx][t] - 1.96 * sigma)));
        }
        ctx.step(String.format("预测区间（95%%）：最优方法 ±1.96σ（σ≈%.0f 件）→ 推荐采用 %s 滚动更新预测",
                sigma, METHOD_NAMES[bestIdx]),
                Map.of("sigma", round2(sigma)));

        // 输出指标（FR-007）
        List<Map<String, Object>> errorItems = new ArrayList<>();
        List<String> curveNames = new ArrayList<>();
        List<List<Number>> curveData = new ArrayList<>();
        for (int i = 0; i < METHOD_KEYS.length; i++) {
            if (mapes[i] != Double.MAX_VALUE) {
                errorItems.add(Map.of("name", METHOD_NAMES[i], "value", round2(mapes[i] * 100)));
            }
        }
        List<Number> xCurve = new ArrayList<>();
        for (int t = 0; t < periods; t++) {
            xCurve.add(t + 1);
        }
        curveNames.add("实际需求");
        List<Number> actualData = new ArrayList<>();
        for (double v : demand) {
            actualData.add(round2(v));
        }
        curveData.add(actualData);
        for (int i = 0; i < METHOD_KEYS.length; i++) {
            if (mapes[i] != Double.MAX_VALUE) {
                List<Number> fcData = new ArrayList<>();
                for (int t = 0; t < periods; t++) {
                    fcData.add(t < train ? null : round2(forecasts[i][t]));
                }
                curveNames.add(METHOD_NAMES[i]);
                curveData.add(fcData);
            }
        }
        Map<String, Object> curve = new LinkedHashMap<>();
        curve.put("x", xCurve);
        List<Map<String, Object>> curveSeries = new ArrayList<>();
        for (int i = 0; i < curveNames.size(); i++) {
            curveSeries.add(Map.of("name", curveNames.get(i), "data", curveData.get(i)));
        }
        curve.put("series", curveSeries);
        Map<String, Object> interval = new LinkedHashMap<>();
        interval.put("x", xInt);
        interval.put("series", List.of(
                Map.of("name", "上界", "data", upper),
                Map.of("name", "下界", "data", lower)));
        ctx.output("error_compare", "各方法MAD/MSE/MAPE", "compare", errorItems, "%");
        ctx.output("forecast_curve", "预测vs实际曲线", "series", curve, "件");
        ctx.output("prediction_interval", "预测区间", "series", interval, "件");
        ctx.output("best_method", "最优方法推荐", "scalar", METHOD_NAMES[bestIdx], null);
    }

    private static boolean selected(String method, String key) {
        return "all".equals(method) || method.equals(key);
    }

    /** 生成需求序列（固定种子 42，保证确定性且 validate 可复算）。 */
    private static double[] generate(int periods, double trend, int seasonPeriod, double seasonAmp, double noise) {
        Random rnd = new Random(42L);
        double[] d = new double[periods];
        for (int t = 0; t < periods; t++) {
            double level = 1000 * (1 + trend * (t + 1));
            double seasonal = 1 + seasonAmp * Math.sin(2 * Math.PI * t / seasonPeriod);
            double err = 1 + noise * rnd.nextGaussian();
            d[t] = Math.max(50, level * seasonal * err);
        }
        return d;
    }

    /** 返回各期预测值（测试区有效，训练区为 0）。1 步滚动预测（用实际值更新）。 */
    private static double[] forecast(String key, double[] demand, int train, int seasonPeriod) {
        int n = demand.length;
        double[] fc = new double[n];
        if ("ma".equals(key)) {
            int w = Math.min(seasonPeriod, train);
            for (int t = train; t < n; t++) {
                double sum = 0;
                for (int i = t - w; i < t; i++) {
                    sum += demand[i];
                }
                fc[t] = sum / w;
            }
        } else if ("ses".equals(key)) {
            double alpha = 0.3;
            double s = mean(demand, 0, train);
            for (int t = train; t < n; t++) {
                fc[t] = s;
                s = alpha * demand[t] + (1 - alpha) * s;
            }
        } else if ("holt".equals(key)) {
            double alpha = 0.3, beta = 0.1;
            double l = demand[0];
            double b = (demand[train - 1] - demand[0]) / (train - 1);
            for (int t = train; t < n; t++) {
                fc[t] = l + b;
                double prevL = l;
                l = alpha * demand[t] + (1 - alpha) * (l + b);
                b = beta * (l - prevL) + (1 - beta) * b;
            }
        } else { // holt_winters：乘法季节指数 + Holt
            int p = seasonPeriod;
            double meanAll = mean(demand, 0, train);
            double[] idx = new double[p];
            for (int k = 0; k < p; k++) {
                double sum = 0;
                int cnt = 0;
                for (int t = k; t < train; t += p) {
                    sum += demand[t];
                    cnt++;
                }
                idx[k] = cnt > 0 ? (sum / cnt) / meanAll : 1.0;
            }
            double alpha = 0.3, beta = 0.1;
            double l = demand[0] / idx[0];
            double b = (demand[train - 1] / idx[(train - 1) % p] - l) / (train - 1);
            for (int t = train; t < n; t++) {
                fc[t] = (l + b) * idx[t % p];
                double prevL = l;
                l = alpha * (demand[t] / idx[t % p]) + (1 - alpha) * (l + b);
                b = beta * (l - prevL) + (1 - beta) * b;
            }
        }
        return fc;
    }

    private static double mean(double[] d, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += d[i];
        }
        return sum / (to - from);
    }

    private static double mape(double[] d, double[] fc, int train) {
        double sum = 0;
        for (int t = train; t < d.length; t++) {
            sum += Math.abs(fc[t] - d[t]) / d[t];
        }
        return sum / (d.length - train);
    }

    private static double mad(double[] d, double[] fc, int train) {
        double sum = 0;
        for (int t = train; t < d.length; t++) {
            sum += Math.abs(fc[t] - d[t]);
        }
        return sum / (d.length - train);
    }

    private static double mse(double[] d, double[] fc, int train) {
        double sum = 0;
        for (int t = train; t < d.length; t++) {
            sum += (fc[t] - d[t]) * (fc[t] - d[t]);
        }
        return sum / (d.length - train);
    }
}
