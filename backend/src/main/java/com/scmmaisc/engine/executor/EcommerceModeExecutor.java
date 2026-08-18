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
 * B2B/B2C/C2C/O2O 电商模式物流适配仿真执行器（T055，CH4-002）。
 * 模型：六种电商模式的订单特征（批量/频次/时效/退货）→ 五种物流方案（整车/零担/快递/
 * 即时配送/到店自提）的成本-时效-体验三维评估（退货率推高成本率）→ 推荐最优物流组合，
 * 并输出 模式×方案 适用条件矩阵。确定性模型：seed 无关（FR-008）。
 */
@Component
public class EcommerceModeExecutor implements ScenarioExecutor {

    private static final Set<String> MODES = Set.of("b2b", "b2c", "c2c", "o2o", "b2g", "c2b");
    private static final Set<String> REQUIREMENTS = Set.of("30min", "same_day", "next_day", "3_5days");
    private static final String[] MODE_NAMES = {"B2B", "B2C", "C2C", "O2O", "B2G", "C2B"};
    private static final String[] MODE_CODES = {"b2b", "b2c", "c2c", "o2o", "b2g", "c2b"};
    private static final String[] SCHEME_NAMES = {"整车", "零担", "快递", "即时配送", "到店自提"};
    private static final double[] SCHEME_BASE_COST = {2.0, 3.0, 5.0, 8.0, 1.0};   // 基准单均成本（元）
    private static final double[] SCHEME_TIME = {72, 48, 24, 0.5, 24};            // 方案时效（小时）

    @Override
    public String engineKey() {
        return "ecommerce-mode";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "mode", MODES, errors);
        doubleParam(params, "order_value", 10, 100000, errors);
        intParam(params, "daily_orders", 100, 500000, errors);
        enumParam(params, "delivery_requirement", REQUIREMENTS, errors);
        Double returnRate = doubleParam(params, "return_rate", 0.02, 0.3, errors);
        doubleParam(params, "delivery_radius", 3, 3000, errors);
        // 约束 cost_rate_ok：高退货率将推高成本率
        if (errors.isEmpty() && returnRate != null && returnRate > 0.25) {
            errors.add("cost_rate_ok 约束不满足：return_rate (" + returnRate + ") 必须 ≤ 0.25");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String mode = String.valueOf(params.get("mode"));
        double orderValue = ((Number) params.get("order_value")).doubleValue();
        int dailyOrders = ((Number) params.get("daily_orders")).intValue();
        String requirement = String.valueOf(params.get("delivery_requirement"));
        double returnRate = ((Number) params.get("return_rate")).doubleValue();
        double deliveryRadius = ((Number) params.get("delivery_radius")).doubleValue();

        // 步骤 1：电商模式订单特征分析
        String[] modeDesc = {
                "批量大/频次低/时效宽松/退货低",   // b2b
                "批量小/频次高/时效高/退货中高",   // b2c
                "零星/频次低/时效中/退货中",       // c2c
                "即时/高频/时效最高/退货低",       // o2o
                "批量大/低频/时效宽松/退货低",     // b2g
                "定制/低频/时效中/退货低"          // c2b
        };
        ctx.step(String.format("电商模式 %s：%s；客单价 %.0f 元、日单量 %d、时效要求 %s、"
                        + "退货率 %.0f%%、配送半径 %.0f km",
                modeName(mode), modeDesc[indexOf(mode)], orderValue, dailyOrders, requirement,
                returnRate * 100, deliveryRadius),
                Map.of("mode", mode, "order_value", orderValue));

        // 步骤 2：物流方案成本-时效测算（退货率推高成本）
        double reqHours = switch (requirement) {
            case "30min" -> 0.5;
            case "same_day" -> 12.0;
            case "next_day" -> 24.0;
            default -> 72.0;
        };
        double returnFactor = 1 + returnRate * 0.8;
        double[] schemeCost = new double[5];
        double[] ontime = new double[5];
        for (int i = 0; i < 5; i++) {
            schemeCost[i] = (SCHEME_BASE_COST[i] + deliveryRadius * schemeRadiusRate(i)) * returnFactor;
            ontime[i] = Math.max(70, 99.5 - Math.max(0, SCHEME_TIME[i] - reqHours) / reqHours * 20);
        }

        // 步骤 3：三维评估与最优推荐（成本 40% + 时效 40% + 模式适配 20%）
        double[] fit = new double[5];
        for (int i = 0; i < 5; i++) {
            double costScore = 100 - Math.min(100, schemeCost[i] / orderValue * 100 * 6);
            double timeScore = ontime[i];
            double modeFit = modeFit(mode, i);
            fit[i] = costScore * 0.4 + timeScore * 0.4 + modeFit * 0.2;
        }
        int best = 0;
        for (int i = 1; i < 5; i++) {
            if (fit[i] > fit[best]) {
                best = i;
            }
        }
        List<Map<String, Object>> recommend = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            recommend.add(Map.of("name", SCHEME_NAMES[i], "value", round2(fit[i])));
        }
        double costRate = schemeCost[best] / orderValue * 100;
        ctx.step(String.format("三维评估：最优物流方案 %s（综合 %.1f 分）；物流成本率 %.2f%%、"
                        + "时效达成率 %.1f%%（退货率 %.0f%% 使成本上浮 %.0f%%）",
                SCHEME_NAMES[best], fit[best], costRate, ontime[best], returnRate * 100, returnRate * 80),
                Map.of("best_scheme", SCHEME_NAMES[best], "cost_rate", round2(costRate)));

        // 步骤 4：模式×方案适用条件矩阵
        double[][] matrix = new double[6][5];
        for (int m = 0; m < 6; m++) {
            for (int s = 0; s < 5; s++) {
                matrix[m][s] = modeFit(MODE_CODES[m], s);
            }
        }
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", List.of(MODE_NAMES));
        heatmap.put("columns", List.of(SCHEME_NAMES));
        List<List<Double>> data = new ArrayList<>();
        for (int m = 0; m < 6; m++) {
            List<Double> row = new ArrayList<>();
            for (int s = 0; s < 5; s++) {
                row.add(matrix[m][s]);
            }
            data.add(row);
        }
        heatmap.put("data", data);
        ctx.step("适用矩阵：整车/零担适合 B2B/B2G 大批量，快递适合 B2C/C2C，即时配送适合 O2O，"
                        + "到店自提适合高退货 C2C/B2C 逆向场景",
                Map.of("mode_recommend", SCHEME_NAMES[best]));

        // 输出指标（FR-007）
        ctx.output("cost_rate", "物流成本率", "scalar", round2(costRate), "%");
        ctx.output("ontime_rate", "时效达成率", "gauge", round2(ontime[best]), "%");
        ctx.output("mode_recommend", "最优物流模式推荐", "compare", recommend, "分");
        ctx.output("fit_matrix", "各模式适用条件矩阵", "heatmap", heatmap, null);
    }

    private int indexOf(String mode) {
        return switch (mode) {
            case "b2b" -> 0;
            case "b2c" -> 1;
            case "c2c" -> 2;
            case "o2o" -> 3;
            case "b2g" -> 4;
            default -> 5;
        };
    }

    private String modeName(String mode) {
        return MODE_NAMES[indexOf(mode)];
    }

    private double schemeRadiusRate(int i) {
        return switch (i) {
            case 0 -> 0.005;   // 整车：半径影响小
            case 1 -> 0.01;    // 零担
            case 2 -> 0.05;    // 快递
            case 3 -> 0.5;     // 即时配送：半径敏感
            default -> 0.0;    // 自提
        };
    }

    private double modeFit(String mode, int scheme) {
        return switch (mode) {
            case "b2b" -> scheme <= 1 ? 90 : scheme == 2 ? 60 : 30;   // 整车/零担
            case "b2c" -> scheme == 2 ? 90 : scheme == 4 ? 60 : 45;   // 快递
            case "c2c" -> scheme == 2 || scheme == 1 ? 80 : 50;
            case "o2o" -> scheme == 3 ? 95 : 30;
            case "b2g" -> scheme == 0 ? 95 : scheme == 1 ? 70 : 30;
            default -> scheme == 2 || scheme == 1 ? 80 : 50;          // c2b
        };
    }
}
