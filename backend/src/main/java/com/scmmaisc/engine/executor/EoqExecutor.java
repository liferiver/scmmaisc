package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EOQ 经济订货批量模型执行器（T025，CH2-003）。
 * 教材公式：Q* = √(2·C2·D/C1)；年总成本 C(Q) = C1·Q/2 + C2·D/Q；ROP = d·L。
 * 参数 key（T024 约定）：annual_demand / order_cost / holding_cost / lead_time /
 * daily_demand（可选，默认 D/365）/ order_qty（可选，0=使用 Q*，约束 ±50%）。
 * 确定性模型：无随机调用，seed 无关（FR-008）。
 */
@Component
public class EoqExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "eoq";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Integer demand = intParam(params, "annual_demand", 100, 100_000, errors);
        Double orderCost = doubleParam(params, "order_cost", 10, 5_000, errors);
        Double holdingCost = doubleParam(params, "holding_cost", 1, 500, errors);
        Double leadTime = doubleParam(params, "lead_time", 1, 30, errors);

        if (params.containsKey("daily_demand")) {
            doubleParam(params, "daily_demand", 0.0001, 10_000, errors);
        }
        if (params.containsKey("order_qty")) {
            intParam(params, "order_qty", 0, Integer.MAX_VALUE, errors);
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        // 约束：order_qty（若指定）偏离 Q* 不得超过 ±50%
        int qty = params.containsKey("order_qty") ? ((Number) params.get("order_qty")).intValue() : 0;
        if (qty > 0) {
            double qStar = qStar(demand, orderCost, holdingCost);
            if (qty < 0.5 * qStar || qty > 1.5 * qStar) {
                errors.add(String.format("order_qty 偏离 Q* 超过 ±50%%（当前 %d，Q* = %.0f）", qty, qStar));
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
        int demand = ((Number) params.get("annual_demand")).intValue();
        double orderCost = ((Number) params.get("order_cost")).doubleValue();
        double holdingCost = ((Number) params.get("holding_cost")).doubleValue();
        double leadTime = ((Number) params.get("lead_time")).doubleValue();
        double daily = params.containsKey("daily_demand")
                ? ((Number) params.get("daily_demand")).doubleValue()
                : demand / 365.0;
        int qty = params.containsKey("order_qty") ? ((Number) params.get("order_qty")).intValue() : 0;

        double qStar = qStar(demand, orderCost, holdingCost);
        double q = qty > 0 ? qty : qStar;
        double rop = daily * leadTime;
        double totalCost = holdingCost * q / 2 + orderCost * demand / q;
        double orderCount = Math.round(demand / q);
        double avgInventory = q / 2;

        // 步骤 1：公式计算
        ctx.step(String.format("计算经济订货批量：Q* = √(2×C2×D/C1) = √(2×%.0f×%d÷%.0f) ≈ %.0f 件",
                orderCost, demand, holdingCost, qStar),
                Map.of("annual_demand", demand, "order_cost", orderCost, "holding_cost", holdingCost,
                        "q_star", round2(qStar)));

        // 步骤 2：再订货点
        Map<String, Object> ropData = new LinkedHashMap<>();
        ropData.put("rop", Math.round(rop));
        ropData.put("daily_demand", round2(daily));
        ropData.put("lead_time", leadTime);
        ctx.step(String.format("再订货点：ROP = d×L = %.2f×%.0f ≈ %d 件", daily, leadTime, Math.round(rop)), ropData);

        // 步骤 3：年总成本曲线（0.5Q*~1.5Q*，25 点，含 Q*）
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            double x = qStar * (0.5 + i / 24.0);
            xs.add(round2(x));
            ys.add(round2(holdingCost * x / 2 + orderCost * demand / x));
        }
        Map<String, Object> curve = new LinkedHashMap<>();
        curve.put("x", xs);
        curve.put("series", List.of(Map.of("name", "年总成本", "data", ys)));
        ctx.step(String.format("年总成本曲线：Q 在 [%.0f, %.0f] 区间采样 25 点，U 形曲线最低点即 Q*",
                0.5 * qStar, 1.5 * qStar), curve);

        // 步骤 4：逐日库存模拟（365 天锯齿曲线，仅展示）
        List<Integer> days = new ArrayList<>();
        List<Double> saw = new ArrayList<>();
        double inv = q;
        for (int day = 1; day <= 365; day++) {
            inv -= daily;
            if (inv <= 0) {
                inv += q;
            }
            days.add(day);
            saw.add(round2(inv));
        }
        Map<String, Object> sawtooth = new LinkedHashMap<>();
        sawtooth.put("x", days);
        sawtooth.put("series", List.of(Map.of("name", "库存水平", "data", saw)));
        ctx.step(String.format("逐日库存模拟（365 天，Q=%.0f 件）：库存到 0 即补货，锯齿曲线见数据", q), sawtooth);

        // 步骤 5：输出汇总
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_cost", round2(totalCost));
        summary.put("order_count", Math.round(orderCount));
        summary.put("avg_inventory", round2(avgInventory));
        ctx.step(String.format("汇总：年总成本 %.0f 元，年订货 %d 次，平均库存 %.0f 件",
                totalCost, Math.round(orderCount), avgInventory), summary);

        // 输出指标（FR-007，声明顺序固定）
        ctx.output("q_star", "经济订货批量 Q*", "scalar", round2(qStar), "件");
        ctx.output("total_cost", "年总成本", "scalar", round2(totalCost), "元");
        ctx.output("annual_cost_curve", "年总成本曲线", "series", curve, null);
        ctx.output("order_count", "年订货次数", "scalar", Math.round(orderCount), "次");
        ctx.output("avg_inventory", "平均库存水平", "scalar", round2(avgInventory), "件");
        ctx.output("rop", "再订货点 ROP", "scalar", Math.round(rop), "件");
    }

    private static double qStar(int demand, double orderCost, double holdingCost) {
        return Math.sqrt(2 * orderCost * demand / holdingCost);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Integer intParam(Map<String, Object> params, String key, int min, int max, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof Number)) {
            errors.add("参数 " + key + " 必须为整数");
            return null;
        }
        int v = ((Number) raw).intValue();
        if (v < min || v > max) {
            errors.add(key + " 超出范围 [" + min + ", " + max + "]");
            return null;
        }
        return v;
    }

    private static Double doubleParam(Map<String, Object> params, String key, double min, double max, List<String> errors) {
        Object raw = params.get(key);
        if (raw == null) {
            errors.add("参数 " + key + " 缺失");
            return null;
        }
        if (!(raw instanceof Number)) {
            errors.add("参数 " + key + " 必须为数值");
            return null;
        }
        double v = ((Number) raw).doubleValue();
        if (v < min || v > max) {
            errors.add(key + " 超出范围 [" + min + ", " + max + "]");
            return null;
        }
        return v;
    }
}
