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

/**
 * 机器学习 vs 深度学习需求预测对比仿真执行器（T062，CH11-002）。
 * 模型（轻量启发式）：合成多维历史需求（趋势 + 周季节性 + 促销脉冲 + 噪声）→ 特征工程
 * （滞后特征/滚动统计/日期编码）→ 时间序列交叉验证（训练/验证/测试，防数据泄露）→ 五类模型
 * 的 MAPE 启发式评分（ARIMA 基线 < 线性回归 < 随机森林 < XGBoost < LSTM，随超参数提升）→
 * 特征重要性、训练/推理时间与促销期精度对比，突出促销期传统方法失真而 ML/DL 可捕捉。
 */
@Component
public class MlForecastExecutor implements ScenarioExecutor {

    private static final double BASE_DEMAND = 500.0;   // 日需求基数（件）

    @Override
    public String engineKey() {
        return "ml-forecast";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "history_days", 365, 1095, errors);
        doubleParam(params, "promo_frequency", 0.05, 0.2, errors);
        intParam(params, "lag_order", 7, 30, errors);
        intParam(params, "xgb_estimators", 100, 500, errors);
        intParam(params, "xgb_max_depth", 3, 10, errors);
        intParam(params, "lstm_hidden", 16, 128, errors);
        intParam(params, "lstm_layers", 1, 3, errors);
        doubleParam(params, "lstm_lr", 0.0001, 0.01, errors);
        Double train = doubleParam(params, "train_ratio", 0.5, 0.8, errors);
        Double val = doubleParam(params, "val_ratio", 0.1, 0.3, errors);
        if (errors.isEmpty() && train != null && val != null && train + val >= 1) {
            errors.add("cv_ok 约束不满足：时间序列交叉验证需先训练后测试，避免数据泄露（训练/验证/测试=0.6/0.2/0.2）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int days = ((Number) params.get("history_days")).intValue();
        double promoFreq = ((Number) params.get("promo_frequency")).doubleValue();
        int lag = ((Number) params.get("lag_order")).intValue();
        int estimators = ((Number) params.get("xgb_estimators")).intValue();
        int depth = ((Number) params.get("xgb_max_depth")).intValue();
        int hidden = ((Number) params.get("lstm_hidden")).intValue();
        int layers = ((Number) params.get("lstm_layers")).intValue();
        double lr = ((Number) params.get("lstm_lr")).doubleValue();
        double trainRatio = ((Number) params.get("train_ratio")).doubleValue();
        double valRatio = ((Number) params.get("val_ratio")).doubleValue();

        // 步骤 1：合成历史需求与特征工程（促销脉冲 → 传统方法失真的根源）
        int testDays = days - (int) (days * (trainRatio + valRatio));
        List<Double> actual = new ArrayList<>();
        int promoCount = 0;
        for (int t = 1; t <= days; t++) {
            double season = 1 + 0.15 * Math.sin(2 * Math.PI * t / 7);   // 周季节性
            boolean promo = ctx.random().nextDouble() < promoFreq;
            if (promo) {
                promoCount++;
            }
            actual.add(round2(BASE_DEMAND * season * (promo ? 2.2 : 1)
                    * (1 + 0.05 * ctx.random().nextGaussian())));
        }
        ctx.step(String.format("合成 %d 天历史需求（基准 %.0f 件，促销事件 %d 次，占比 %.1f%%）；"
                        + "特征工程：滞后 %d 阶 + 滚动均值 + 日期编码（促销/节假日/天气/竞品价）",
                days, BASE_DEMAND, promoCount, promoCount * 100.0 / days, lag),
                Map.of("history_days", days, "promo_events", promoCount, "lag_order", lag));

        // 步骤 2：时间序列交叉验证划分（防数据泄露）
        int trainEnd = (int) (days * trainRatio);
        int valEnd = trainEnd + (int) (days * valRatio);
        ctx.step(String.format("时序划分：训练 %d 天（%.0f%%）→ 验证 %d 天（%.0f%%）→ 测试 %d 天（%.0f%%）；"
                        + "严格按时间先后切分，测试期含促销样本 %.0f%%",
                trainEnd, trainRatio * 100, valEnd - trainEnd, valRatio * 100,
                days - valEnd, (days - valEnd) * 100.0 / days, promoCount * 100.0 / days),
                Map.of("train_days", trainEnd, "val_days", valEnd - trainEnd, "test_days", days - valEnd));

        // 步骤 3：五类模型 MAPE 对比（启发式评分，随超参数单调变化）
        double mapeArima = 18.0;                                             // 基线：季节朴素
        double mapeLr = 14.0;                                                // 线性回归
        double mapeRf = 10.5 - lag * 0.03;                                   // 随机森林
        double mapeXgb = 9.5 - depth * 0.25 - estimators * 0.0015;           // XGBoost
        double mapeLstm = 8.5 - hidden * 0.012 - layers * 0.5 + Math.log10(lr) * 2.0; // LSTM
        List<Map<String, Object>> mapeCompare = List.of(
                Map.of("name", "ARIMA(基线)", "value", round2(mapeArima)),
                Map.of("name", "线性回归", "value", round2(mapeLr)),
                Map.of("name", "随机森林", "value", round2(mapeRf)),
                Map.of("name", "XGBoost", "value", round2(mapeXgb)),
                Map.of("name", "LSTM", "value", round2(mapeLstm)));
        ctx.step(String.format("测试集 MAPE：ARIMA %.1f%% / 线性回归 %.1f%% / 随机森林 %.1f%% / "
                        + "XGBoost %.1f%% / LSTM %.1f%%（树模型与深度随超参数增强）",
                mapeArima, mapeLr, mapeRf, mapeXgb, mapeLstm),
                Map.of("mape_compare", mapeCompare));

        // 步骤 4：特征重要性排名与训练/推理时间
        List<Map<String, Object>> importance = List.of(
                Map.of("name", "促销标志", "value", 35),
                Map.of("name", "价格", "value", 20),
                Map.of("name", "滞后" + lag + "日", "value", 15),
                Map.of("name", "节假日", "value", 12),
                Map.of("name", "天气", "value", 10),
                Map.of("name", "滚动均值", "value", 8));
        List<Map<String, Object>> timeCompare = List.of(
                Map.of("name", "ARIMA", "value", 12.0),
                Map.of("name", "线性回归", "value", 25.0),
                Map.of("name", "随机森林", "value", 180.0),
                Map.of("name", "XGBoost", "value", round2(estimators * 0.6)),
                Map.of("name", "LSTM", "value", round2(hidden * layers * 0.4)));
        ctx.step(String.format("特征重要性 TOP：促销标志 35%%（促销期数据是区分模型的关键特征）；"
                        + "训练时间：XGBoost %.0f ms / LSTM %.0f ms",
                estimators * 0.6, hidden * layers * 0.4),
                Map.of("feature_importance", importance, "time_compare", timeCompare));

        // 步骤 5：促销期预测精度与预测曲线（促销期传统方法失真）
        List<Map<String, Object>> promoAccuracy = List.of(
                Map.of("name", "ARIMA-促销期", "value", round2(mapeArima + 10)),
                Map.of("name", "线性回归-促销期", "value", round2(mapeLr + 6)),
                Map.of("name", "随机森林-促销期", "value", round2(mapeRf + 2)),
                Map.of("name", "XGBoost-促销期", "value", round2(mapeXgb + 1.5)),
                Map.of("name", "LSTM-促销期", "value", round2(mapeLstm + 1)));
        int window = Math.min(60, testDays);
        List<Double> x = new ArrayList<>();
        List<Double> actualCurve = new ArrayList<>();
        List<Double> xgbCurve = new ArrayList<>();
        List<Double> lstmCurve = new ArrayList<>();
        for (int i = 0; i < window; i++) {
            double v = actual.get(days - window + i);
            x.add((double) (i + 1));
            actualCurve.add(v);
            xgbCurve.add(round2(v * (1 + mapeXgb / 100 * ctx.random().nextGaussian() * 0.5)));
            lstmCurve.add(round2(v * (1 + mapeLstm / 100 * ctx.random().nextGaussian() * 0.5)));
        }
        Map<String, Object> curves = new LinkedHashMap<>();
        curves.put("x", x);
        curves.put("series", List.of(
                Map.of("name", "实际需求", "data", actualCurve),
                Map.of("name", "XGBoost预测", "data", xgbCurve),
                Map.of("name", "LSTM预测", "data", lstmCurve)));
        ctx.step(String.format("促销期精度：ARIMA %.1f%% vs LSTM %.1f%%（促销标志特征使 ML/DL 捕捉 "
                        + "非线性脉冲，传统模型严重失真）",
                mapeArima + 10, mapeLstm + 1),
                Map.of("promo_accuracy", promoAccuracy, "forecast_curves", curves));

        // 输出指标（FR-007）
        ctx.output("mape_compare", "各模型MAPE对比", "compare", mapeCompare, "%");
        ctx.output("forecast_curves", "预测曲线(多模型)", "series", curves, "件");
        ctx.output("feature_importance", "特征重要性排名", "compare", importance, null);
        ctx.output("time_compare", "训练/推理时间", "compare", timeCompare, "ms");
        ctx.output("promo_accuracy", "促销期预测精度对比", "compare", promoAccuracy, "%");
    }
}
