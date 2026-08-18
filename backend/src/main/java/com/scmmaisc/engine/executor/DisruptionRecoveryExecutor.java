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
 * 全球供应链中断恢复仿真执行器（T061，CH10-004）。
 * 模型：正常运行 → 突发中断（工厂关闭/运河堵塞/港口罢工，时长服从对数正态分布）→ 危机管理：
 * 评估影响 → 激活备选供应商（切换时间 + 成本上浮）→ 调用安全库存 → 产能重新分配 → 客户分级
 * 保障 → 逐日恢复服务水平并计算损失利润、客户流失、激活-恢复甘特图与韧性投资 ROI。
 */
@Component
public class DisruptionRecoveryExecutor implements ScenarioExecutor {

    private static final double DAILY_VALUE_PER_CUSTOMER = 2_000.0;  // 单客户日销售额（元）
    private static final double PROFIT_MARGIN = 0.25;                // 利润率

    @Override
    public String engineKey() {
        return "disruption-recovery";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "disruption_node", Set.of("shanghai_factory", "suez_canal", "port_strike"), errors);
        Double backup = doubleParam(params, "backup_switch_time", 5, 30, errors);
        doubleParam(params, "backup_cost_premium", 10, 50, errors);
        Double safety = doubleParam(params, "safety_stock_days", 7, 60, errors);
        intParam(params, "affected_customers", 10, 500, errors);
        doubleParam(params, "capacity_flexibility", 0, 1, errors);
        doubleParam(params, "penalty_daily_ratio", 0.001, 0.05, errors);
        if (errors.isEmpty() && safety != null && safety < 7) {
            errors.add("key_customer_ok 约束不满足：关键客户必须 3 天内恢复供应（需足够安全库存缓冲）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 对数正态分布单次抽样（mean/σ 为原始尺度参数）。 */
    private static double lognormalSample(double mean, double sigma, SimContext ctx) {
        double sigmaLog = Math.sqrt(Math.log(1 + sigma * sigma / (mean * mean)));
        double muLog = Math.log(mean) - sigmaLog * sigmaLog / 2;
        return Math.exp(muLog + sigmaLog * ctx.random().nextGaussian());
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String node = String.valueOf(params.get("disruption_node"));
        double backupSwitch = ((Number) params.get("backup_switch_time")).doubleValue();
        double premium = ((Number) params.get("backup_cost_premium")).doubleValue();   // %
        double safetyDays = ((Number) params.get("safety_stock_days")).doubleValue();
        int customers = ((Number) params.get("affected_customers")).intValue();
        double flex = ((Number) params.get("capacity_flexibility")).doubleValue();
        double penaltyRatio = ((Number) params.get("penalty_daily_ratio")).doubleValue();

        // 步骤 1：中断事件与影响评估（时长抽样）
        double duration = lognormalSample(20, 10, ctx);   // 对数正态（mean 20 天，σ 10）
        int durDays = Math.max(7, (int) Math.round(duration));
        double dailyRevenue = customers * DAILY_VALUE_PER_CUSTOMER;
        ctx.step(String.format("中断节点：%s；抽样中断时长 %.0f 天；日销售额 %,.0f 元（%d 个受影响客户）",
                nodeName(node), duration, dailyRevenue, customers),
                Map.of("disruption_duration", round2(duration), "daily_revenue", round2(dailyRevenue)));

        // 步骤 2：危机管理启动（安全库存 → 备选供应商 → 产能重分配 → 客户分级）
        double backupVolume = Math.min(1, 0.5 + flex * 0.5);  // 备选供应商承接比例
        ctx.step(String.format("危机管理：安全库存覆盖 %.0f 天（维持 60%% 服务）→ %.0f 天后启用备选供应商"
                        + "（成本上浮 %.0f%%，承接 %.0f%% 产能）→ 其他工厂重分配 %.0f%%",
                safetyDays, backupSwitch, premium, backupVolume * 100, flex * 100),
                Map.of("backup_switch_time", round2(backupSwitch), "backup_volume", round2(backupVolume * 100)));

        // 步骤 3：逐日恢复仿真（服务水平 = 安全库存 60% → 备选爬坡 → 恢复）
        List<Double> days = new ArrayList<>();
        List<Double> serviceCurve = new ArrayList<>();
        List<Double> capacityCurve = new ArrayList<>();
        double lostProfit = 0;
        double penalty = 0;
        double backupCost = 0;
        int maxDay = durDays + 5;
        for (int t = 1; t <= maxDay; t++) {
            double service;
            if (t <= safetyDays && t <= durDays) {
                service = 0.6;                                    // 安全库存缓冲
            } else if (t <= backupSwitch) {
                service = 0.1;                                    // 切换窗口期
            } else {
                service = Math.min(1, 0.55 + backupVolume * 0.45 + flex * 0.15
                        * Math.min(1, (t - backupSwitch) / 10.0)); // 备选+重分配爬坡
            }
            service = Math.min(1, service);
            days.add((double) t);
            serviceCurve.add(round2(service * 100));
            capacityCurve.add(round2((t > backupSwitch ? backupVolume + flex : flex) * 100));
            if (t <= durDays) {
                lostProfit += dailyRevenue * (1 - service) * PROFIT_MARGIN;
                if (t > 3) {
                    penalty += dailyRevenue * (1 - service) * penaltyRatio;
                }
                if (t > backupSwitch) {
                    backupCost += dailyRevenue * backupVolume * premium / 100;
                }
            }
        }
        double totalLoss = lostProfit + penalty + backupCost;
        ctx.step(String.format("恢复仿真 %d 天：损失利润 %,.0f 元 + 违约罚金 %,.0f 元 + 备选溢价 %,.0f 元"
                        + " = 中断总损失 %,.0f 元",
                maxDay, lostProfit, penalty, backupCost, totalLoss),
                Map.of("lost_profit", round2(lostProfit), "penalty", round2(penalty),
                        "backup_cost", round2(backupCost)));

        // 步骤 4：客户流失与优先级保障
        double customerLoss = duration > safetyDays
                ? customers * Math.min(0.3, (duration - safetyDays) * 0.01) : 0;
        int lost = (int) Math.round(customerLoss);
        double keyCustomerService = Math.min(100, 60 + safetyDays * 1.5);   // 关键客户 3 天内恢复
        ctx.step(String.format("客户流失 %d 个（流失率 %.1f%%）；关键客户 %.0f 天内恢复供应（分级保障）",
                lost, customers > 0 ? customerLoss / customers * 100 : 0, keyCustomerService / 1.5),
                Map.of("customer_loss", lost, "key_customer_service", round2(keyCustomerService)));

        // 步骤 5：韧性投资 ROI 与甘特图汇总
        double noMitigationLoss = dailyRevenue * durDays * (1 - 0.1) * PROFIT_MARGIN
                + dailyRevenue * durDays * 0.9 * penaltyRatio;
        double investment = safetyDays * 5_000 + premium / 100 * dailyRevenue * durDays * backupVolume;
        double roi = investment > 0 ? (noMitigationLoss - totalLoss) / investment * 100 : 0;
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("x", days);
        timeline.put("series", List.of(
                Map.of("name", "服务水平", "data", serviceCurve),
                Map.of("name", "备用产能", "data", capacityCurve)));
        ctx.step(String.format("韧性投资 ROI = %.1f%%（无预案损失 %,.0f 元 → 有预案损失 %,.0f 元，"
                        + "投入 %,.0f 元）",
                roi, noMitigationLoss, totalLoss, investment),
                Map.of("resilience_roi", round2(roi)));

        // 输出指标（FR-007）
        ctx.output("recovery_timeline", "中断恢复时间", "series", timeline, "天");
        ctx.output("lost_profit", "中断期间损失利润", "scalar", round2(totalLoss), "元");
        ctx.output("customer_loss", "客户流失数", "scalar", lost, "个");
        ctx.output("activation_gantt", "备选方案激活-恢复甘特图", "series", timeline, null);
        ctx.output("resilience_roi", "韧性投资ROI", "scalar", round2(roi), "%");
    }

    private static String nodeName(String node) {
        return switch (node) {
            case "suez_canal" -> "苏伊士运河堵塞";
            case "port_strike" -> "港口罢工";
            default -> "上海工厂关闭";
        };
    }
}
