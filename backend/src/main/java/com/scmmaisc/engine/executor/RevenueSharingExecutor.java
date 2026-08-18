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
 * 收益共享合同仿真执行器（T059，CH8-006，报童模型 + 均匀需求）。
 * 模型：供应商低批发价 w + 零售商保留 φ 比例零售收入；零售商订货量 Qr=F⁻¹((φp-w)/(φp-v))，
 * 集中决策 Q*=F⁻¹((p-c)/(p-v))（c=50 教材典型值）；协调批发价 w*=φp-(φp-v)(p-c)/(p-v)
 * （教材 p=100/φ=0.8/c=50/v=20 → w*=42.5，Qr=Q*=350）；对比各方利润、链效率、协调条件偏差
 * 与适用条件（Blockbuster 音像租赁双赢案例）。
 */
@Component
public class RevenueSharingExecutor implements ScenarioExecutor {

    private static final double UNIT_COST = 50.0; // 生产成本 c（教材典型值，无独立参数）

    @Override
    public String engineKey() {
        return "revenue-sharing";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        doubleParam(params, "retail_price", 50, 200, errors);
        Double w = doubleParam(params, "wholesale_price", 20, 60, errors);
        Double phi = doubleParam(params, "revenue_share", 0.5, 0.9, errors);
        doubleParam(params, "salvage_value", 5, 20, errors);
        doubleParam(params, "demand_min", 50, 500, errors);
        doubleParam(params, "demand_max", 100, 1000, errors);
        if (errors.isEmpty() && w != null && phi != null) {
            // 约束 w_positive：收益共享下 w 可低于成本，但供应商总收益需覆盖成本
            if (w <= 0 || phi >= 1) {
                errors.add("w_positive 约束不满足：批发价需 > 0 且收益保留比例 < 1");
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
        double phi = ((Number) params.get("revenue_share")).doubleValue();
        double v = ((Number) params.get("salvage_value")).doubleValue();
        double a = ((Number) params.get("demand_min")).doubleValue();
        double span = ((Number) params.get("demand_max")).doubleValue() - a;

        // 步骤 1：集中决策 Q*（c=50）与收益共享下零售商订货量 Qr
        double qStar = uniformQuantile((p - UNIT_COST) / (p - v), a, a + span);
        double qr = uniformQuantile((phi * p - w) / (phi * p - v), a, a + span);
        ctx.step(String.format("集中决策 Q*=%.0f 件；收益共享合同（w=%.1f, φ=%.2f）Qr=%.0f 件",
                        qStar, w, phi, qr),
                Map.of("optimal_order_qty", round2(qStar), "retailer_order_qty", round2(qr)));

        // 步骤 2：各方利润与供应链效率
        double sales = expectedSales(qr, a, a + span);
        double unsold = expectedUnsold(qr, a, a + span);
        double retailerProfit = phi * p * sales + v * unsold - w * qr;
        double supplierProfit = (1 - phi) * p * sales + w * qr - UNIT_COST * qr;
        double chainProfit = retailerProfit + supplierProfit;
        double salesStar = expectedSales(qStar, a, a + span);
        double unsoldStar = expectedUnsold(qStar, a, a + span);
        double chainStar = p * salesStar + v * unsoldStar - UNIT_COST * qStar;
        double efficiency = chainStar > 0 ? chainProfit / chainStar * 100 : 100;
        List<Map<String, Object>> profitCompare = List.of(
                Map.of("name", "零售商利润", "value", round2(retailerProfit)),
                Map.of("name", "供应商利润", "value", round2(supplierProfit)),
                Map.of("name", "供应链总利润", "value", round2(chainProfit)));
        ctx.step(String.format("零售商利润 %,.0f 元 / 供应商利润 %,.0f 元 / 链总 %,.0f 元（效率 %.1f%%）",
                        retailerProfit, supplierProfit, chainProfit, efficiency),
                Map.of("profit_compare", profitCompare, "chain_efficiency", round2(efficiency)));

        // 步骤 3：协调条件验证（协调批发价 w* 与偏差 %）
        double wStar = phi * p - (phi * p - v) * (p - UNIT_COST) / (p - v);
        double deviation = Math.abs(w - wStar) / Math.max(1e-9, wStar) * 100;
        ctx.step(String.format("协调批发价 w*=%.1f（当前 w=%.1f，偏差 %.1f%%）→ Qr %s Q*",
                        wStar, w, deviation, Math.abs(qr - qStar) < 1e-6 ? "=" : Math.abs(qr - qStar) < 1 ? "≈" : "≠"),
                Map.of("coordination_w_star", round2(wStar), "coordination_check", round2(deviation)));

        // 步骤 4：适用条件分析与汇总（Blockbuster 案例）
        String applicability = "适用条件：需求不确定性强、产品残值高（音像/软件/书籍/时装）；"
                + "低批发价+收入分成降低零售商进货风险，供应商以规模销量补偿分成损失；"
                + "经典案例 Blockbuster 音像租赁：收益共享使双方利润双升，供应链实现协调。";
        ctx.step(applicability, Map.of("applicability", applicability));

        // 输出指标（FR-007）
        ctx.output("optimal_order_qty", "供应链最优订货量", "scalar", round2(qStar), "件");
        ctx.output("retailer_order_qty", "零售商最优订货量", "scalar", round2(qr), "件");
        ctx.output("chain_efficiency", "供应链效率", "gauge", List.of(
                Map.of("name", "供应链效率", "value", round2(efficiency))), "%");
        ctx.output("profit_compare", "各方利润对比", "compare", profitCompare, "元");
        ctx.output("coordination_check", "协调条件验证", "scalar", round2(deviation), "%");
        ctx.output("applicability", "适用条件分析", "scalar", applicability, null);
    }
}
