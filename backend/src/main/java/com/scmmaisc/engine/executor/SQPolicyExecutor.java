package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 随机需求库存 (s,Q) 策略仿真执行器（T053，CH2-006；SC-010 公式契约）。
 * 模型：SS = zα·σ·√L，s = μ·L + SS（正态/泊松需求，zα 教材近似值 z(0.95)=1.65）；
 * 逐日仿真 365 天：库存≤s 触发订货 Q，提前期 L 后到货，统计缺货天数/缺货量 →
 * 实际 CSL 与 fill rate；年总成本 = 持有成本 + 缺货成本。
 * 仿真随机部分依赖 ctx.random()（R-05 种子可复现），公式部分 seed 无关。
 */
@Component
public class SQPolicyExecutor implements ScenarioExecutor {

    private static final Set<String> DIST_TYPES = Set.of("normal", "poisson");

    @Override
    public String engineKey() {
        return "s-q-policy";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Object raw = params.get("demand_dist");
        if (raw == null) {
            errors.add("参数 demand_dist 缺失");
        } else if (!(raw instanceof Map<?, ?> group)) {
            errors.add("参数 demand_dist 必须为分布对象");
        } else {
            Object type = group.get("distType");
            if (type == null) {
                errors.add("demand_dist.distType 缺失");
            } else if (!DIST_TYPES.contains(String.valueOf(type))) {
                errors.add("demand_dist.distType 必须为 " + DIST_TYPES + " 之一");
            } else if ("normal".equals(String.valueOf(type))) {
                checkDistField(group, "mean", 0.1, 100000, errors);
                checkDistField(group, "sigma", 0.01, 1000, errors);
            } else {
                checkDistField(group, "lambda", 0.1, 100000, errors);
            }
        }
        intParam(params, "order_qty", 50, 5000, errors);
        doubleParam(params, "lead_time", 1, 14, errors);
        doubleParam(params, "service_level", 0.90, 0.999, errors);
        doubleParam(params, "holding_cost", 1, 500, errors);
        doubleParam(params, "stockout_cost", 10, 5000, errors);
        return errors;
    }

    private void checkDistField(Map<?, ?> group, String key, double min, double max, List<String> errors) {
        Object v = group.get(key);
        if (v == null) {
            errors.add("demand_dist." + key + " 缺失");
        } else if (!(v instanceof Number)) {
            errors.add("demand_dist." + key + " 必须为数值");
        } else {
            double d = ((Number) v).doubleValue();
            if (d < min || d > max) {
                errors.add("demand_dist." + key + " 超出范围 [" + min + ", " + max + "]");
            }
        }
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        @SuppressWarnings("unchecked")
        Map<String, Object> dist = (Map<String, Object>) params.get("demand_dist");
        String distType = String.valueOf(dist.get("distType"));
        double mu;
        double sigma;
        if ("normal".equals(distType)) {
            mu = ((Number) dist.get("mean")).doubleValue();
            sigma = ((Number) dist.get("sigma")).doubleValue();
        } else {
            double lambda = ((Number) dist.get("lambda")).doubleValue();
            mu = lambda;
            sigma = Math.sqrt(lambda);
        }
        double orderQty = ((Number) params.get("order_qty")).doubleValue();
        double leadTime = ((Number) params.get("lead_time")).doubleValue();
        double serviceLevel = ((Number) params.get("service_level")).doubleValue();
        double holdingCost = ((Number) params.get("holding_cost")).doubleValue();
        double stockoutCost = ((Number) params.get("stockout_cost")).doubleValue();

        // 步骤 1：安全库存计算
        double z = NormalDist.zNormal(serviceLevel);
        double safetyStock = z * sigma * Math.sqrt(leadTime);
        double reorderPoint = mu * leadTime + safetyStock;
        ctx.step(String.format("需求分布：%s（μ=%.1f、σ=%.1f），目标服务水平 α=%.3f → zα=%.2f；"
                        + "安全库存 SS = zα·σ·√L = %.2f×%.1f×√%.1f = %.2f 单位",
                "normal".equals(distType) ? "正态" : "泊松", mu, sigma, serviceLevel, z,
                z, sigma, leadTime, safetyStock),
                Map.of("z_alpha", round2(z), "safety_stock", round2(safetyStock)));

        // 步骤 2：再订货点
        ctx.step(String.format("再订货点 s = μ·L + SS = %.1f×%.1f + %.2f = %.2f 单位（库存 ≤ s 时订货 %s 单位）",
                mu, leadTime, safetyStock, reorderPoint, orderQty == Math.floor(orderQty) ? String.format("%.0f", orderQty) : String.valueOf(orderQty)),
                Map.of("reorder_point", round2(reorderPoint)));

        // 步骤 3：逐日仿真 365 天（提前期 L 天后到货）
        double inventory = reorderPoint + orderQty;
        double outstanding = 0;
        double dueDay = -1;
        int stockoutDays = 0;
        double shortageUnits = 0;
        double totalDemand = 0;
        double invSum = 0;
        int orders = 0;
        for (int day = 1; day <= 365; day++) {
            if (dueDay > 0 && day >= dueDay) {
                inventory += orderQty;
                outstanding = 0;
            }
            double demand = nextDemand(ctx, distType, mu, sigma);
            totalDemand += demand;
            if (demand > inventory) {
                stockoutDays++;
                shortageUnits += demand - inventory;
                inventory = 0;
            } else {
                inventory -= demand;
            }
            if (inventory <= reorderPoint && outstanding == 0) {
                outstanding = orderQty;
                dueDay = day + leadTime;
                orders++;
            }
            invSum += inventory;
        }
        double avgInventory = invSum / 365.0;
        double actualCsl = (1 - stockoutDays / 365.0) * 100;
        double fillRate = totalDemand == 0 ? 100 : (1 - shortageUnits / totalDemand) * 100;
        ctx.step(String.format("仿真完成：订货 %d 次、缺货 %d 天（%.2f 单位）；平均库存 %.1f 单位",
                orders, stockoutDays, shortageUnits, avgInventory),
                Map.of("order_count", orders, "stockout_days", stockoutDays,
                        "avg_inventory", round2(avgInventory)));

        // 步骤 4：服务水平与年总成本
        double totalCost = holdingCost * avgInventory + stockoutCost * shortageUnits;
        ctx.step(String.format("实际 CSL %.2f%%、fill rate %.2f%%（目标 α=%.1f%%）；"
                        + "年总成本 = %.1f×%.1f + %.0f×%.2f = %.2f 元",
                actualCsl, fillRate, serviceLevel * 100,
                holdingCost, avgInventory, stockoutCost, shortageUnits, totalCost),
                Map.of("actual_csl", round2(actualCsl), "fill_rate", round2(fillRate),
                        "total_cost", round2(totalCost)));

        // 输出指标（FR-007）
        ctx.output("reorder_point", "最优再订货点s", "scalar", round2(reorderPoint), "单位");
        ctx.output("safety_stock", "安全库存量", "scalar", round2(safetyStock), "单位");
        ctx.output("actual_csl", "实际CSL", "gauge", round2(actualCsl), "%");
        ctx.output("fill_rate", "实际fill rate", "gauge", round2(fillRate), "%");
        ctx.output("total_cost", "年总成本", "scalar", round2(totalCost), "元");
    }

    /** 需求采样：正态用 Box-Muller 高斯；泊松用 Knuth 逆变换（λ 较小，365 天循环有界）。 */
    private double nextDemand(SimContext ctx, String distType, double mu, double sigma) {
        if ("normal".equals(distType)) {
            return Math.max(0, mu + sigma * ctx.random().nextGaussian());
        }
        double lambda = mu;
        double l = Math.exp(-lambda);
        double p = 1;
        int k = 0;
        do {
            k++;
            p *= ctx.random().nextDouble();
        } while (p > l && k < 1000);
        return k - 1;
    }
}
