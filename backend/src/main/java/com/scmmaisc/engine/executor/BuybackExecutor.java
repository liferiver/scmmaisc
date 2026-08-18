package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 回购合同仿真执行器（T059，CH8-005，报童模型 + 均匀需求）。
 * 模型：零售商在 (w,b) 合同下选择订货量 Qr=F⁻¹((p-w+b-s)/(p-s))；集中决策 Q*=F⁻¹((p-w)/(p-s))
 * （c=w）；恰选 b 使 Qr=Q* 实现供应链协调（教材 p=100/w=50/b=20/s=20 → Qr=Q*=350）；
 * 对比批发价合同（b=0）与回购合同的各方利润、链效率，以及 (w,b) 组合的协调区间热力图。
 */
@Component
public class BuybackExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "buyback";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "retail_price", 50, 200, errors);
        doubleParam(params, "wholesale_price", 10, 150, errors);
        Double b = doubleParam(params, "buyback_price", 5, 100, errors);
        Double s = doubleParam(params, "salvage_value", 5, 20, errors);
        doubleParam(params, "demand_min", 50, 500, errors);
        doubleParam(params, "demand_max", 100, 1000, errors);
        if (errors.isEmpty() && b != null && s != null) {
            // 约束 b_in_range：回购价需满足 s < b < w
            Object wRaw = params.get("wholesale_price");
            double w = ((Number) wRaw).doubleValue();
            if (b <= s) {
                errors.add("b_in_range 约束不满足：回购价需高于残值（s < b），当前 b=" + b + " ≤ s=" + s);
            }
            if (b >= w) {
                errors.add("b_in_range 约束不满足：回购价需低于批发价（b < w），当前 b=" + b + " ≥ w=" + w);
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    /** 均匀分布 [a,b] 分位数 F⁻¹(u)。 */
    private static double uniformQuantile(double u, double a, double b) {
        return a + Math.max(0, Math.min(1, u)) * (b - a);
    }

    /** 期望销量 E[min(D,q)]（均匀分布）。 */
    private static double expectedSales(double q, double a, double b) {
        double span = b - a;
        if (q <= a) {
            return q;
        }
        if (q >= b) {
            return (a + b) / 2;
        }
        return q - (q - a) * (q - a) / (2 * span);
    }

    /** 期望未售量 E[max(q-D,0)]。 */
    private static double expectedUnsold(double q, double a, double b) {
        return Math.max(0, q - expectedSales(q, a, b));
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double p = ((Number) params.get("retail_price")).doubleValue();
        double w = ((Number) params.get("wholesale_price")).doubleValue();
        double b = ((Number) params.get("buyback_price")).doubleValue();
        double s = ((Number) params.get("salvage_value")).doubleValue();
        double a = ((Number) params.get("demand_min")).doubleValue();
        double span = ((Number) params.get("demand_max")).doubleValue() - a;

        // 步骤 1：集中决策最优订货量（c=w）与零售商最优订货量（回购合同）
        double qStar = uniformQuantile((p - w) / (p - s), a, a + span);
        double qr = uniformQuantile((p - w + b - s) / (p - s), a, a + span);
        ctx.step(String.format("集中决策 Q*=%.0f 件；回购合同下零售商最优订货量 Qr=%.0f 件（b=%.0f）",
                        qStar, qr, b),
                Map.of("optimal_order_qty", round2(qStar), "retailer_order_qty", round2(qr)));

        // 步骤 2：各方利润（零售/供应商/链总）与供应链效率
        double sales = expectedSales(qr, a, a + span);
        double unsold = expectedUnsold(qr, a, a + span);
        double retailerProfit = p * sales - w * qr + b * unsold;
        double supplierProfit = - (b - s) * unsold; // c=w：供应商仅承担回购价差
        double chainProfit = retailerProfit + supplierProfit;
        double salesStar = expectedSales(qStar, a, a + span);
        double unsoldStar = expectedUnsold(qStar, a, a + span);
        double chainStar = p * salesStar - w * qStar + s * unsoldStar; // 集中链 = p·E[min] − c·q + s·E[unsold]
        double efficiency = chainStar > 0 ? chainProfit / chainStar * 100 : 100;
        List<Map<String, Object>> profitCompare = List.of(
                Map.of("name", "零售商利润", "value", round2(retailerProfit)),
                Map.of("name", "供应商利润", "value", round2(supplierProfit)),
                Map.of("name", "供应链总利润", "value", round2(chainProfit)));
        ctx.step(String.format("零售商利润 %,.0f 元 / 供应商利润 %,.0f 元 / 链总 %,.0f 元（效率 %.1f%%）",
                        retailerProfit, supplierProfit, chainProfit, efficiency),
                Map.of("profit_compare", profitCompare, "chain_efficiency", round2(efficiency)));

        // 步骤 3：批发价合同（b=0）对照与 vs_wholesale 对比
        double qw = uniformQuantile((p - w - s) / (p - s), a, a + span);
        double salesW = expectedSales(qw, a, a + span);
        double retailW = p * salesW - w * qw + s * expectedUnsold(qw, a, a + span);
        double supplierW = 0.0; // c=w 下批发价合同供应商无利可图（教学对照：体现回购风险分担）
        List<Map<String, Object>> vsWholesale = List.of(
                Map.of("name", "批发价-零售商", "value", round2(retailW)),
                Map.of("name", "批发价-供应商", "value", round2(supplierW)),
                Map.of("name", "回购-零售商", "value", round2(retailerProfit)),
                Map.of("name", "回购-供应商", "value", round2(supplierProfit)));
        ctx.step(String.format("批发价合同（b=0）Qw=%.0f 件：零售商 %,.0f 元；回购合同零售商 %,.0f 元（风险分担提升订货）",
                        qw, retailW, retailerProfit),
                Map.of("vs_wholesale", vsWholesale));

        // 步骤 4：(w,b) 组合协调区间热力图（效率 %）
        List<String> rows = new ArrayList<>();
        List<String> cols = new ArrayList<>();
        List<List<Double>> grid = new ArrayList<>();
        for (int wi = 0; wi < 8; wi++) {
            double wc = 10 + wi * 20; // w = 10..150
            cols.add(String.valueOf((int) wc));
        }
        for (int bi = 0; bi < 10; bi++) {
            double bc = 5 + bi * 10; // b = 5..95
            rows.add(String.valueOf((int) bc));
            List<Double> rowData = new ArrayList<>();
            for (int wi = 0; wi < 8; wi++) {
                double wc = 10 + wi * 20;
                if (bc >= wc) {
                    rowData.add(0.0); // 无效组合 b ≥ w
                    continue;
                }
                double q = uniformQuantile((p - wc + bc - s) / (p - s), a, a + span);
                double sel = expectedSales(q, a, a + span);
                double uns = expectedUnsold(q, a, a + span);
                double chain = p * sel - wc * q + s * uns; // 链总 = p·E[min] − w·q + s·E[unsold]
                double eff = chainStar > 0 ? chain / chainStar * 100 : 0;
                rowData.add(round2(Math.max(0, Math.min(100, eff))));
            }
            grid.add(rowData);
        }
        Map<String, Object> heatmap = new java.util.LinkedHashMap<>();
        heatmap.put("rows", rows);
        heatmap.put("columns", cols);
        heatmap.put("data", grid);
        ctx.step("协调区间热力图生成（横轴批发价 w，纵轴回购价 b；s < b < w 区域内效率趋近 100%）",
                Map.of("coordination_heatmap", heatmap));

        // 输出指标（FR-007）
        ctx.output("optimal_order_qty", "供应链最优订货量", "scalar", round2(qStar), "件");
        ctx.output("retailer_order_qty", "零售商最优订货量", "scalar", round2(qr), "件");
        ctx.output("profit_compare", "各方利润", "compare", profitCompare, "元");
        ctx.output("chain_efficiency", "供应链效率(实际/集中最优)", "gauge", List.of(
                Map.of("name", "供应链效率", "value", round2(efficiency))), "%");
        ctx.output("coordination_heatmap", "协调区间(w,b组合)", "heatmap", heatmap, null);
        ctx.output("vs_wholesale", "vs批发价合同对比", "compare", vsWholesale, "元");
    }
}
