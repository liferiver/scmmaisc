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
 * 京东自建物流 + 第三方物流混合模式仿真执行器（T055，CH4-004，角色扮演）。
 * 模型：自营仓配覆盖城市数（仓数×20 上限）→ 自营区 211 时效 vs 外包区时效延长 →
 * 总成本 = 自营投资摊销 + 自营/外包单均成本 × 城市订单量 → 绘制总成本-自营覆盖率
 * 曲线求最优自营/外包边界；对比自营 vs 3PL 单均成本。确定性模型：seed 无关（FR-008）。
 */
@Component
public class JdModeExecutor implements ScenarioExecutor {

    private static final Set<String> STANDARDS = Set.of("211", "next_day", "two_day");
    private static final double ORDERS_PER_CITY = 100.0;   // 每城市年订单量（万单）
    private static final double DEPRECIATION = 0.12;       // 自营投资年摊销比例

    @Override
    public String engineKey() {
        return "jd-mode";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer cities = intParam(params, "city_count", 10, 300, errors);
        Integer warehouses = intParam(params, "warehouse_count", 3, 50, errors);
        intParam(params, "station_count", 20, 2000, errors);
        doubleParam(params, "self_invest", 5000, 500000, errors);
        doubleParam(params, "self_unit_cost", 5, 20, errors);
        doubleParam(params, "pl_unit_cost", 4, 15, errors);
        enumParam(params, "delivery_standard", STANDARDS, errors);
        // 约束 coverage_ok：核心城市必须自营覆盖（仓库数×20 ≥ 覆盖城市数）
        if (errors.isEmpty() && cities != null && warehouses != null && warehouses * 20 < cities) {
            errors.add("coverage_ok 约束不满足：warehouse_count × 20 (" + warehouses * 20
                    + ") 必须 ≥ city_count (" + cities + ")");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int cityCount = ((Number) params.get("city_count")).intValue();
        int warehouseCount = ((Number) params.get("warehouse_count")).intValue();
        int stationCount = ((Number) params.get("station_count")).intValue();
        double selfInvest = ((Number) params.get("self_invest")).doubleValue();
        double selfUnitCost = ((Number) params.get("self_unit_cost")).doubleValue();
        double plUnitCost = ((Number) params.get("pl_unit_cost")).doubleValue();
        String standard = String.valueOf(params.get("delivery_standard"));

        int selfCapacity = warehouseCount * 20;
        int selfCities = Math.min(cityCount, selfCapacity);

        // 步骤 1：网络配置与覆盖测算
        ctx.step(String.format("自建仓配网络：%d 个区域仓+前置仓、%d 个配送站 → 自营覆盖上限 %d 城"
                        + "（仓×20）；当前目标覆盖 %d 城 → 自营 %d 城 + 3PL %d 城，时效标准 %s",
                warehouseCount, stationCount, selfCapacity, cityCount, selfCities, cityCount - selfCities,
                standard),
                Map.of("self_cities", selfCities, "pl_cities", cityCount - selfCities));

        // 步骤 2：总成本-自营覆盖率曲线
        List<Double> cx = new ArrayList<>();
        List<Double> cy = new ArrayList<>();
        double minCost = Double.POSITIVE_INFINITY;
        int bestCover = 0;
        for (int c = 0; c <= 100; c += 10) {
            double investCost = selfInvest * c / 100.0 * DEPRECIATION;
            double selfCost = cityCount * c / 100.0 * selfUnitCost * ORDERS_PER_CITY;
            double plCost = cityCount * (1 - c / 100.0) * plUnitCost * ORDERS_PER_CITY;
            double total = investCost + selfCost + plCost;
            cx.add((double) c);
            cy.add(round2(total));
            if (total < minCost) {
                minCost = total;
                bestCover = c;
            }
        }
        Map<String, Object> curve = new LinkedHashMap<>();
        curve.put("x", cx);
        curve.put("series", List.of(Map.of("name", "总成本(万元/年)", "data", cy)));
        int optimalCities = (int) Math.round(cityCount * bestCover / 100.0);
        ctx.step(String.format("总成本-覆盖率曲线：成本最低点 自营覆盖率 %d%%（%d 城，年成本 %.0f 万元）",
                bestCover, optimalCities, minCost),
                Map.of("optimal_coverage", bestCover));

        // 步骤 3：时效达标率（自营高、外包低，按城市加权）
        double selfOntime = switch (standard) {
            case "211" -> 99.5;
            case "next_day" -> 99.0;
            default -> 98.5;
        };
        double plOntime = switch (standard) {
            case "211" -> 92.0;
            case "next_day" -> 95.0;
            default -> 98.0;
        };
        double ontime = (selfCities * selfOntime + (cityCount - selfCities) * plOntime) / cityCount;
        ctx.step(String.format("时效达标率 %.1f%%：自营区 %.1f%%（%s 时效），3PL 区 %.1f%%"
                        + "（时效延长、达标率下降）；低线城市自营成本高是覆盖边界的主要约束",
                ontime, selfOntime, standard, plOntime),
                Map.of("ontime_rate", round2(ontime)));

        // 步骤 4：自营 vs 外包权衡
        List<Map<String, Object>> compareItems = new ArrayList<>();
        compareItems.add(Map.of("name", "自营单均配送成本", "value", round2(selfUnitCost)));
        compareItems.add(Map.of("name", "3PL单均成本", "value", round2(plUnitCost)));
        double investPerOrder = selfInvest * DEPRECIATION / (selfCities * ORDERS_PER_CITY);
        ctx.step(String.format("自营单均 %.1f 元（含摊销 %.1f 元/单） vs 3PL 单均 %.1f 元："
                        + "自营贵在重资产摊销，但时效与服务可控；最优边界 %d 城",
                selfUnitCost, investPerOrder, plUnitCost, optimalCities),
                Map.of("optimal_boundary", optimalCities));

        // 输出指标（FR-007）
        ctx.output("coverage_cost_curve", "总成本-自营覆盖率曲线", "series", curve, "万元");
        ctx.output("ontime_rate", "时效达标率", "gauge", round2(ontime), "%");
        ctx.output("optimal_boundary", "最优自营覆盖边界", "scalar", (double) optimalCities, "城市");
        ctx.output("self_vs_pl", "自营vs外包成本对比", "compare", compareItems, "元/单");
    }
}
