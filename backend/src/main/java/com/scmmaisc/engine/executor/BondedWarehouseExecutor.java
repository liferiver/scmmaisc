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
import static com.scmmaisc.engine.executor.ExecutorSupport.series;

/**
 * 保税仓布局与运营仿真执行器（T056，CH5-006）。
 * 模型：批量入区（关税暂免）→ 订单拣货 → 清关（行邮税/跨境综合税，50 元免征额）→ 国内快递 →
 * 签收；单均成本 = 头程摊销 + 仓储(平均持有 45 天) + 清关 + 快递 + 税额；滞销品折价 + 仓储
 * 双损失；备货准确率 vs 滞销率敏感性曲线；与直邮/海外仓模式对比（元/单）。
 */
@Component
public class BondedWarehouseExecutor implements ScenarioExecutor {

    private static final Set<String> CATEGORIES =
            Set.of("beauty", "baby", "health", "food", "appliance");
    /** 品类单均货值（元）：美妆/母婴/保健/食品/家电。 */
    private static final Map<String, Double> ORDER_VALUE = Map.of(
            "beauty", 120.0, "baby", 150.0, "health", 100.0,
            "food", 80.0, "appliance", 500.0);

    @Override
    public String engineKey() {
        return "bonded-warehouse";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        String category = enumParam(params, "goods_category", CATEGORIES, errors);
        Integer sku = intParam(params, "sku_count", 50, 5000, errors);
        Integer stock = intParam(params, "sku_stock_qty", 100, 50000, errors);
        Double taxRate = doubleParam(params, "tax_rate", 0.091, 0.25, errors);
        Double quota = doubleParam(params, "tax_free_quota", 0, 5000, errors);
        Double slowRate = doubleParam(params, "slow_sell_rate", 0.05, 0.3, errors);
        Double fee = doubleParam(params, "storage_fee", 0.01, 0.5, errors);
        if (errors.isEmpty() && category != null && sku != null && stock != null
                && taxRate != null && quota != null && slowRate != null && fee != null) {
            // 约束 quota_ok：个人年度免征限额不超过 5000 元
            if (quota > 5000) {
                errors.add("quota_ok 约束不满足：个人年度免征税额需 ≤ 5000 元");
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
        String category = String.valueOf(params.get("goods_category"));
        int sku = ((Number) params.get("sku_count")).intValue();
        int stock = ((Number) params.get("sku_stock_qty")).intValue();
        double taxRate = ((Number) params.get("tax_rate")).doubleValue();
        double quota = ((Number) params.get("tax_free_quota")).doubleValue();
        double slowRate = ((Number) params.get("slow_sell_rate")).doubleValue();
        double fee = ((Number) params.get("storage_fee")).doubleValue();

        double orderValue = ORDER_VALUE.get(category);
        long totalStock = (long) sku * stock;

        // 步骤 1：入区备货（关税暂免，滞销风险即沉没成本）
        ctx.step(String.format("备货 %d SKU × %d 件 = %,.0f 件入保税区（暂免关税），滞销率 %.0f%%",
                        sku, stock, (double) totalStock, slowRate * 100),
                Map.of("total_stock", totalStock, "slow_sell_rate", slowRate));

        // 步骤 2：订单履约（拣货 → 清关 → 快递，累计时效）
        double duty = orderValue * taxRate <= 50 ? 0 : orderValue * taxRate; // 行邮税 50 元免征额
        double storagePerUnit = fee * 45; // 平均持有 45 天
        double unitCost = 0.5 + storagePerUnit + 1.5 + 5.5 + duty;
        List<Double> cum = new ArrayList<>();
        double t = 0.1;
        cum.add(round2(t));
        t += 0.3; // 保税仓拣货
        cum.add(round2(t));
        t += 0.6; // 清关放行
        cum.add(round2(t));
        t += 1.8; // 国内快递
        cum.add(round2(t));
        t += 0.2; // 签收
        cum.add(round2(t));
        ctx.step(String.format("下单→拣货→清关(%.1f%%税率%s)→快递→签收：全程 %.1f 天",
                        taxRate * 100, duty > 0 ? "，单件税 " + (long) duty + " 元" : "（免征）", t),
                Map.of("delivery_days", round2(t), "duty_per_unit", round2(duty)));

        // 步骤 3：成本核算与滞销损失
        double slowStock = totalStock * slowRate;
        double slowLoss = (slowStock * orderValue * 0.3 + slowStock * fee * 180) / 10000; // 万元
        double storageYear = totalStock * fee * 365 / 10000; // 全年仓储费（万元）
        ctx.step(String.format("单均物流成本 %.2f 元/单（头程0.5+仓储%.2f+清关1.5+快递5.5+税%.0f）",
                        unitCost, storagePerUnit, duty),
                Map.of("unit_logistics_cost", round2(unitCost),
                        "storage_annual_wan", round2(storageYear)));

        // 步骤 4：备货准确率-滞销损失敏感性 & 模式对比
        List<Double> x = new ArrayList<>();
        List<Double> accuracy = new ArrayList<>();
        List<Double> lossCurve = new ArrayList<>();
        for (int r = 5; r <= 30; r += 5) {
            double rate = r / 100.0;
            x.add((double) r);
            accuracy.add(round2((1 - rate) * 100));
            lossCurve.add(round2((totalStock * rate * orderValue * 0.3
                    + totalStock * rate * fee * 180) / 10000));
        }
        Map<String, Object> accLoss = new LinkedHashMap<>();
        accLoss.put("x", x);
        accLoss.put("series", List.of(
                Map.of("name", "备货准确率(%)", "data", accuracy),
                Map.of("name", "滞销损失(万元)", "data", lossCurve)));
        List<Map<String, Object>> modeCompare = List.of(
                Map.of("name", "保税仓", "value", round2(unitCost)),
                Map.of("name", "直邮", "value", round2(28 + duty)),
                Map.of("name", "海外仓", "value", 15.0));
        ctx.step(String.format("滞销损失 %.2f 万元（%s，滞销率 %.0f%%）；保税 vs 直邮 vs 海外仓 对比完成",
                        slowLoss, category, slowRate * 100),
                Map.of("slow_sell_loss", round2(slowLoss), "mode_compare", modeCompare));

        // 输出指标（FR-007）
        ctx.output("unit_logistics_cost", "单均物流成本", "scalar", round2(unitCost), "元/单");
        ctx.output("delivery_time", "配送时效", "series",
                series(List.of(1, 2, 3, 4, 5), "累计时效(天)", cum), "天");
        ctx.output("slow_sell_loss", "滞销损失", "scalar", round2(slowLoss), "万元");
        ctx.output("accuracy_vs_loss", "备货准确率vs滞销率", "series", accLoss, null);
        ctx.output("mode_compare", "保税vs直邮vs海外仓对比", "compare", modeCompare, "元/单");
    }
}
