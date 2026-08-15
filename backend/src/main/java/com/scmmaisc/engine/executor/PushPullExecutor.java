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
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 推动/拉动/推拉结合策略仿真执行器（T029，CH7-002）。
 * 模型：Push 按预测生产（高库存 + 预测失配损失）、Pull 按订单生产（零库存但响应 = 生产提前期）、
 * Push-Pull 在解耦点（CODP）前 Push、后 Pull；扫描 5 个解耦点位置求最优。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class PushPullExecutor implements ScenarioExecutor {

    private static final Set<String> CODP = Set.of("raw", "semi", "finished", "distribution", "retail");
    /** 解耦点枚举值（顺序与 CODP_PUSH/CODP_NAMES 对齐） */
    private static final List<String> CODP_ORDER = List.of("raw", "semi", "finished", "distribution", "retail");
    /** 各解耦点的"提前 Push 比例"：0=完全拉式，1=完全推式 */
    private static final double[] CODP_PUSH = {0.10, 0.35, 0.70, 0.85, 0.95};
    private static final String[] CODP_NAMES = {"原材料", "半成品", "成品", "分销", "零售"};

    @Override
    public String engineKey() {
        return "push-pull";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double customization = doubleParam(params, "customization", 0, 1, errors);
        Double mape = doubleParam(params, "forecast_mape", 0.1, 0.4, errors);
        Double cv = doubleParam(params, "demand_cv", 0.1, 0.8, errors);
        Double leadTime = doubleParam(params, "production_lead_time", 5, 60, errors);
        Double tolerance = doubleParam(params, "customer_wait_tolerance", 1, 14, errors);
        String codp = enumParam(params, "decoupling_point", CODP, errors);
        Double holding = doubleParam(params, "holding_cost", 1, 100, errors);
        Double stockout = doubleParam(params, "stockout_cost", 10, 500, errors);
        if (errors.isEmpty() && customization != null && mape != null && cv != null
                && leadTime != null && tolerance != null && codp != null && holding != null && stockout != null) {
            // 约束 pull_lead_ok：拉动策略响应时间（= 生产提前期）须 ≤ 客户容忍等待时间
            if (leadTime > tolerance) {
                errors.add(String.format("pull_lead_ok 约束不满足：拉动策略响应时间不能超过客户容忍等待时间（生产提前期 %.0f 天 > 容忍 %.0f 天）",
                        leadTime, tolerance));
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
        double f = ((Number) params.get("customization")).doubleValue();
        double mape = ((Number) params.get("forecast_mape")).doubleValue();
        double cv = ((Number) params.get("demand_cv")).doubleValue();
        double leadTime = ((Number) params.get("production_lead_time")).doubleValue();
        String codp = String.valueOf(params.get("decoupling_point"));
        double holding = ((Number) params.get("holding_cost")).doubleValue();
        double stockout = ((Number) params.get("stockout_cost")).doubleValue();

        double D = 1000.0;                       // 日需求（件）
        double annual = D * 365;
        double cvFactor = 1 + cv;

        // 步骤 1：需求特征与策略框架
        ctx.step(String.format("需求特征：日均 %.0f 件，预测误差 MAPE %.0f%%，波动 CV %.0f；产品定制化程度 %.0f", 
                D, mape * 100, cv, f),
                Map.of("customization", round2(f), "forecast_mape", round2(mape)));

        // 步骤 2：Push 策略（按预测生产）
        double invPush = D * leadTime * (1 + mape * (1 + f)) * cvFactor;      // 覆盖提前期 + 预测误差的安全库存
        double mismatchPush = annual * mape * (1 + f) * cvFactor;             // 预测失配件数
        double pushCost = invPush * holding + mismatchPush * stockout;
        ctx.step(String.format("Push：库存 %.0f 件，响应 1 天，预测失配 %.0f 件/年 → 持有+损失成本 %.0f 元",
                invPush, mismatchPush, pushCost),
                Map.of("push_inventory", round2(invPush), "push_cost", round2(pushCost)));

        // 步骤 3：Pull 策略（按订单生产）
        double invPull = D * leadTime * 0.05;                                 // 仅 WIP
        double mismatchPull = 0;
        double pullCost = invPull * holding;
        ctx.step(String.format("Pull：库存 %.0f 件（仅 WIP），响应 %.0f 天（=生产提前期），无预测失配 → 成本 %.0f 元",
                invPull, leadTime, pullCost),
                Map.of("pull_inventory", round2(invPull), "pull_cost", round2(pullCost)));

        // 步骤 4：Push-Pull 与 CODP 扫描
        int idx = CODP_ORDER.indexOf(codp);
        double pSelected = CODP_PUSH[idx];
        double invPp = D * leadTime * pSelected * (1 + mape * (1 - f)) * cvFactor;
        double responsePp = leadTime * (1 - pSelected) + pSelected;           // 已 Push 部分即时，剩余按订单生产
        double mismatchPp = annual * mape * (1 - f) * cvFactor;
        double ppCost = invPp * holding + mismatchPp * stockout;
        // 最优解耦点扫描
        int bestIdx = 0;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < CODP_PUSH.length; i++) {
            double inv = D * leadTime * CODP_PUSH[i] * (1 + mape * (1 - f)) * cvFactor;
            double mm = annual * mape * (1 - f) * cvFactor;
            double cost = inv * holding + mm * stockout;
            if (cost < bestCost) {
                bestCost = cost;
                bestIdx = i;
            }
        }
        ctx.step(String.format("Push-Pull：解耦点=【%s】，Push 比例 %.0f%%；库存 %.0f 件、响应 %.1f 天、失配 %.0f 件 → 成本 %.0f 元",
                CODP_NAMES[idx], pSelected * 100, invPp, responsePp, mismatchPp, ppCost),
                Map.of("push_pull_inventory", round2(invPp), "push_pull_response", round2(responsePp),
                        "push_pull_cost", round2(ppCost)));
        ctx.step(String.format("解耦点扫描：最优位置【%s】（总成本 %.0f 元）——定制化 %.0f 下兼顾库存与响应",
                CODP_NAMES[bestIdx], bestCost, f),
                Map.of("best_decoupling_point", CODP_NAMES[bestIdx], "best_cost", round2(bestCost)));

        // 步骤 5：结论
        String advice = invPp < invPush && responsePp < leadTime
                ? "推拉结合在库存与响应间取得平衡，建议采用"
                : "当前参数下三种策略差异不大，可结合市场细分混合部署";
        ctx.step(String.format("结论：%s（库存：Push %.0f > Push-Pull %.0f > Pull %.0f）", advice, invPush, invPp, invPull),
                Map.of("advice", advice));

        // 输出指标（FR-007）
        Map<String, Object> invSeries = new LinkedHashMap<>();
        invSeries.put("x", List.of("Push", "Pull", "Push-Pull"));
        invSeries.put("series", List.of(Map.of("name", "平均库存水平", "data",
                List.of(round2(invPush), round2(invPull), round2(invPp)))));
        ctx.output("inventory_levels", "三种策略库存水平", "series", invSeries, "件");
        ctx.output("response_time_compare", "响应时间对比", "compare",
                List.of(Map.of("name", "Push", "value", round2(1.0)),
                        Map.of("name", "Pull", "value", round2(leadTime)),
                        Map.of("name", "Push-Pull", "value", round2(responsePp))),
                "天");
        ctx.output("loss_compare", "缺货/滞销损失对比", "compare",
                List.of(Map.of("name", "Push", "value", round2(mismatchPush * stockout)),
                        Map.of("name", "Pull", "value", round2(mismatchPull)),
                        Map.of("name", "Push-Pull", "value", round2(mismatchPp * stockout))),
                "元");
        ctx.output("best_decoupling_point", "最优解耦点", "scalar", CODP_NAMES[bestIdx], null);
        ctx.output("total_cost_compare", "总成本对比", "compare",
                List.of(Map.of("name", "Push", "value", round2(pushCost)),
                        Map.of("name", "Pull", "value", round2(pullCost)),
                        Map.of("name", "Push-Pull", "value", round2(ppCost))),
                "元");
    }
}
