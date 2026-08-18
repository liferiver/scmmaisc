package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.enumParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 海外仓选址与运营仿真执行器（T056，CH5-007）。
 * 模型：头程（元/kg × 0.5kg ÷ 7.2 汇率）→ 目的国海外仓（FBA/第三方/自建，履约费+仓储+本地配送）→
 * 消费者签收 → 退货处置与滞销（LTSF 长期仓储费惩罚）；单均成本对比（美元/单）、配送时效对比、
 * IPI 库存绩效指数（>400 避免 FBA 容量限制）与退货处置效率。
 */
@Component
public class OverseasWarehouseExecutor implements ScenarioExecutor {

    private static final Set<String> COUNTRIES = Set.of("usa", "europe", "japan", "se_asia");
    private static final Set<String> MODES = Set.of("fba", "third_party", "self_built");

    /** 模式属性：履约费(美元/单) / 仓储费系数 / 退货处置基准效率(%) / IPI 加成。 */
    private static final Map<String, double[]> MODE_DATA = Map.of(
            "fba", new double[]{4.0, 1.0, 88, 0},
            "third_party", new double[]{2.5, 0.8, 84, 10},
            "self_built", new double[]{1.5, 0.7, 80, 15});
    /** 目的国本地配送系数：美国/欧洲/日本/东南亚。 */
    private static final Map<String, Double> COUNTRY_FACTOR = Map.of(
            "usa", 1.0, "europe", 1.05, "japan", 1.1, "se_asia", 0.8);

    private static final double AVG_WEIGHT = 0.5; // kg/件
    private static final double AVG_CUBIC = 0.08; // 立方英尺/件
    private static final double FX_RATE = 7.2;    // 元/美元

    @Override
    public String engineKey() {
        return "overseas-warehouse";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        String country = enumParam(params, "target_country", COUNTRIES, errors);
        Integer sku = intParam(params, "sku_count", 20, 2000, errors);
        String mode = enumParam(params, "warehouse_mode", MODES, errors);
        Double headHaul = doubleParam(params, "head_haul_cost", 3, 30, errors);
        Double storage = doubleParam(params, "storage_fee", 0.5, 3.0, errors);
        Double delivery = doubleParam(params, "local_delivery_fee", 3, 8, errors);
        Double returnRate = doubleParam(params, "return_rate", 0.05, 0.25, errors);
        Double returnCost = doubleParam(params, "return_cost", 1, 5, errors);
        Double ltsf = doubleParam(params, "ltsf_fee", 3, 20, errors);
        if (errors.isEmpty() && country != null && sku != null && mode != null
                && headHaul != null && storage != null && delivery != null
                && returnRate != null && returnCost != null && ltsf != null) {
            // 约束 ipi_ok：SKU 过多 → IPI 低于 400 → FBA 容量受限
            if (sku > 2000) {
                errors.add("ipi_ok 约束不满足：SKU 数量需 ≤ 2000（过多备货 SKU 将拉低 IPI 库存绩效指数）");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String country = String.valueOf(params.get("target_country"));
        int sku = ((Number) params.get("sku_count")).intValue();
        String mode = String.valueOf(params.get("warehouse_mode"));
        double headHaul = ((Number) params.get("head_haul_cost")).doubleValue();
        double storage = ((Number) params.get("storage_fee")).doubleValue();
        double delivery = ((Number) params.get("local_delivery_fee")).doubleValue();
        double returnRate = ((Number) params.get("return_rate")).doubleValue();
        double returnCost = ((Number) params.get("return_cost")).doubleValue();
        double ltsf = ((Number) params.get("ltsf_fee")).doubleValue();

        double[] modeData = MODE_DATA.get(mode);
        double fulfill = modeData[0];
        double countryFactor = COUNTRY_FACTOR.get(country);
        double slowRate = Math.min(0.5, 0.08 + returnRate * 0.6);

        // 步骤 1：头程与入库（批量海运/空运至海外仓）
        double headHaulUsd = headHaul * AVG_WEIGHT / FX_RATE;
        ctx.step(String.format("头程 %.0f 元/kg × %.1f kg ÷ %.1f = %.2f 美元/件 → %s %s 仓入库",
                        headHaul, AVG_WEIGHT, FX_RATE, headHaulUsd, country, mode),
                Map.of("head_haul_usd", round2(headHaulUsd), "warehouse_mode", mode));

        // 步骤 2：本地履约（仓储 + 履约费 + 本地配送 + LTSF 计提）
        double storagePerUnit = AVG_CUBIC * 2 * storage * modeData[1]; // 平均持有 2 个月
        double ltsfPerUnit = slowRate * ltsf * AVG_CUBIC;              // 滞销部分按月计提
        double unitCost = headHaulUsd + storagePerUnit + fulfill + delivery * countryFactor
                + returnCost * returnRate + ltsfPerUnit;
        ctx.step(String.format("单均成本 %.2f 美元 = 头程%.2f + 仓储%.2f + 履约%.2f + 配送%.2f + 退货%.2f + LTSF%.2f",
                        unitCost, headHaulUsd, storagePerUnit, fulfill,
                        delivery * countryFactor, returnCost * returnRate, ltsfPerUnit),
                Map.of("unit_cost_usd", round2(unitCost), "storage_per_unit", round2(storagePerUnit),
                        "ltsf_per_unit", round2(ltsfPerUnit)));

        // 步骤 3：退货处置与滞销风险（FBA 容量惩罚机制）
        double slowUnits = sku * 120 * slowRate; // 每 SKU 平均备货 120 件
        double slowRisk = slowUnits * (18 * 0.35 + AVG_CUBIC * ltsf * 6);
        double returnEfficiency = Math.min(98, modeData[2] + (1 - returnRate) * 15);
        ctx.step(String.format("退货率 %.0f%%：处置效率 %.1f%%（%s），滞销 %,.0f 件 → 风险 %.0f 美元",
                        returnRate * 100, returnEfficiency, mode, slowUnits, slowRisk),
                Map.of("slow_units", (long) slowUnits, "slow_sell_risk", round2(slowRisk),
                        "return_efficiency", round2(returnEfficiency)));

        // 步骤 4：IPI 库存绩效与模式对比
        double ipi = Math.max(0, 520 - sku * 0.08 - returnRate * 200 + modeData[3]);
        boolean capacityOk = ipi >= 400;
        List<Map<String, Object>> costCompare = List.of(
                Map.of("name", "海外仓(" + mode + ")", "value", round2(unitCost)),
                Map.of("name", "直邮", "value", 4.5),
                Map.of("name", "保税仓", "value", 1.8));
        List<Map<String, Object>> deliveryCompare = List.of(
                Map.of("name", "海外仓", "value", 3.0),
                Map.of("name", "保税仓", "value", 6.0),
                Map.of("name", "直邮", "value", 14.0));
        ctx.step(String.format("IPI 指数 %.0f（%s 400 分线）：%s", ipi, capacityOk ? "≥" : "<",
                        capacityOk ? "FBA 容量不受限" : "触发 FBA 容量限制"),
                Map.of("ipi_score", round2(ipi), "capacity_ok", capacityOk,
                        "unit_cost_compare", costCompare, "delivery_compare", deliveryCompare));

        // 输出指标（FR-007）
        ctx.output("unit_cost_compare", "单均成本对比(海外仓/直邮/保税)", "compare", costCompare, "美元/单");
        ctx.output("delivery_compare", "配送时效对比", "compare", deliveryCompare, "天");
        ctx.output("ipi_score", "IPI库存绩效指数", "scalar", round2(ipi), null);
        ctx.output("slow_sell_risk", "滞销风险金额", "scalar", round2(slowRisk), "美元");
        ctx.output("return_efficiency", "退货处置效率", "scalar", round2(returnEfficiency), "%");
    }
}
