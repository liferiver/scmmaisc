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
 * 供应链设计综合决策仿真执行器（十步法，T058，CH7-008，综合难度）。
 * 模型：十步法设计决策（市场机会/服务目标/策略/网络/选址/运输/库存/信息系统/组织/绩效）逐项
 * 评分 → 综合 KPI 仪表盘（服务水平/成本效率/响应性/敏捷性/资产效率）→ 全链路成本（设施/信息
 * 系统/服务水平追加，随策略与目标调整）与总预算校验 → 风险-收益分析。
 */
@Component
public class ScmDesignExecutor implements ScenarioExecutor {

    @Override
    public String engineKey() {
        return "scm-design";
    }

    /** 信息系统水平 → 建设成本（万元）。 */
    private static double infoCost(String level) {
        return switch (level) {
            case "smart" -> 250;
            case "integrated" -> 100;
            default -> 0;
        };
    }

    /** 供应链策略 → 成本系数（Push 低成本 / Pull 高响应高成本 / 推拉结合居中）。 */
    private static double strategyFactor(String strategy) {
        return switch (strategy) {
            case "push" -> 0.95;
            case "pull" -> 1.08;
            default -> 1.03;
        };
    }

    /** 全链路成本估算（万元）：基础 800 + 设施 + 信息系统 + 服务水平追加，乘策略系数。 */
    private static double chainCost(double target, int facilities, String info, String strategy) {
        double base = 800 + facilities * 30 + infoCost(info)
                + Math.max(0, (target - 0.95) * 2000);
        return base * strategyFactor(strategy);
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        Double target = doubleParam(params, "customer_service_target", 0.9, 0.999, errors);
        Double budget = doubleParam(params, "total_budget", 100, 10000, errors);
        String strategy = enumParam(params, "supply_strategy", Set.of("push", "pull", "push_pull"), errors);
        Integer facilities = intParam(params, "facility_count", 1, 50, errors);
        String info = enumParam(params, "info_system_level", Set.of("basic", "integrated", "smart"), errors);
        if (errors.isEmpty() && target != null && budget != null && strategy != null
                && facilities != null && info != null) {
            if (target > 0.999) {
                errors.add("service_ok 约束不满足：客户服务目标需 ≤ 99.9%");
            }
            double cost = chainCost(target, facilities, info, strategy);
            if (cost > budget) {
                errors.add("service_ok 约束不满足：全链路成本约 " + round2(cost)
                        + " 万元，超过总预算 " + round2(budget) + " 万元");
            }
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 6;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double target = ((Number) params.get("customer_service_target")).doubleValue();
        double budget = ((Number) params.get("total_budget")).doubleValue();
        String strategy = String.valueOf(params.get("supply_strategy"));
        int facilities = ((Number) params.get("facility_count")).intValue();
        String info = String.valueOf(params.get("info_system_level"));

        // 步骤 1：①市场机会识别 + ②客户服务目标
        List<Map<String, Object>> decisions = new ArrayList<>();
        decisions.add(Map.of("name", "①市场机会识别", "value", 90.0));
        decisions.add(Map.of("name", "②客户服务目标", "value", round2(target * 100)));
        ctx.step(String.format("十步法 ①②：识别市场机会（评分 90）；客户服务目标 %.1f%%（可用订单满足率）",
                        target * 100),
                Map.of("customer_service_target", round2(target)));

        // 步骤 2：③供应链策略 + ④网络结构设计
        String[] stratLabels = {"Push推动", "Pull拉动", "推拉结合"};
        int si = strategy.equals("push") ? 0 : strategy.equals("pull") ? 1 : 2;
        double scoreStrategy = strategy.equals("pull") ? 88 : strategy.equals("push_pull") ? 80 : 60;
        double scoreNetwork = Math.min(95, 70 + facilities);
        decisions.add(Map.of("name", "③供应链策略", "value", round2(scoreStrategy)));
        decisions.add(Map.of("name", "④网络结构设计", "value", round2(scoreNetwork)));
        ctx.step(String.format("十步法 ③④：策略 %s（适配评分 %.0f）；网络结构 %d 个设施（评分 %.0f）",
                        stratLabels[si], scoreStrategy, facilities, scoreNetwork),
                Map.of("supply_strategy", stratLabels[si], "facility_count", facilities));

        // 步骤 3：⑤设施选址 + ⑥运输策略
        double scoreLocation = 85.0;
        double scoreTransport = strategy.equals("pull") ? 90 : strategy.equals("push_pull") ? 85 : 65;
        decisions.add(Map.of("name", "⑤设施选址", "value", scoreLocation));
        decisions.add(Map.of("name", "⑥运输策略", "value", round2(scoreTransport)));
        ctx.step(String.format("十步法 ⑤⑥：设施选址（评分 %.0f）；运输策略（评分 %.0f，%s 侧重整车/零担配比）",
                        scoreLocation, scoreTransport, stratLabels[si]),
                Map.of("location_score", scoreLocation, "transport_score", round2(scoreTransport)));

        // 步骤 4：⑦库存策略 + ⑧信息系统设计
        double scoreInventory = strategy.equals("pull") ? 92 : strategy.equals("push_pull") ? 80 : 62;
        double scoreInfo = info.equals("smart") ? 95 : info.equals("integrated") ? 80 : 55;
        String[] infoNames = {"基础", "集成", "智慧"};
        String infoLabel = infoNames[info.equals("smart") ? 2 : info.equals("integrated") ? 1 : 0];
        decisions.add(Map.of("name", "⑦库存策略", "value", round2(scoreInventory)));
        decisions.add(Map.of("name", "⑧信息系统设计", "value", round2(scoreInfo)));
        ctx.step(String.format("十步法 ⑦⑧：库存策略（评分 %.0f）；信息系统 %s 级（评分 %.0f，建设 %.0f 万元）",
                        scoreInventory, infoLabel, scoreInfo, infoCost(info)),
                Map.of("info_system_level", infoLabel, "info_cost", round2(infoCost(info))));

        // 步骤 5：⑨组织设计 + ⑩绩效体系 + 综合 KPI 仪表盘
        decisions.add(Map.of("name", "⑨组织设计", "value", 85.0));
        decisions.add(Map.of("name", "⑩绩效体系设计", "value", 88.0));
        double cost = chainCost(target, facilities, info, strategy);
        double costEff = Math.min(100, budget / cost * 100);
        double responsive = strategy.equals("pull") ? 92 : strategy.equals("push_pull") ? 78 : 55;
        double agility = strategy.equals("pull") ? 90 : strategy.equals("push_pull") ? 75 : 50;
        double assetEff = Math.max(30, 100 - facilities * 1.2);
        if (info.equals("smart")) {
            responsive += 5;
        }
        List<Map<String, Object>> kpis = List.of(
                Map.of("name", "服务水平", "value", round2(target * 100)),
                Map.of("name", "成本效率", "value", round2(costEff)),
                Map.of("name", "响应性", "value", round2(responsive)),
                Map.of("name", "敏捷性", "value", round2(agility)),
                Map.of("name", "资产效率", "value", round2(assetEff)));
        ctx.step("十步法 ⑨⑩：组织与绩效体系设计完成，综合 KPI 仪表盘已生成",
                Map.of("kpi_dashboard", kpis));

        // 步骤 6：全链路成本与风险-收益评估
        double revenue = budget * 3 * target;
        double net = revenue - cost;
        double riskIndex = (1 - target) * 200 + facilities * 0.6
                + (info.equals("smart") ? 6 : info.equals("integrated") ? 12 : 20);
        List<Map<String, Object>> riskReturn = List.of(
                Map.of("name", "预期年收益", "value", round2(revenue)),
                Map.of("name", "全链路成本", "value", round2(cost)),
                Map.of("name", "净收益", "value", round2(net)),
                Map.of("name", "综合风险指数", "value", round2(riskIndex)));
        String budgetText = cost <= budget
                ? String.format("全链路成本 %,.0f 万元 ≤ 总预算 %,.0f 万元，方案可行", cost, budget)
                : String.format("全链路成本 %,.0f 万元 超过总预算 %,.0f 万元，需调整方案", cost, budget);
        ctx.step(budgetText + String.format("；净收益 %,.0f 万元/年", net),
                Map.of("total_chain_cost", round2(cost), "risk_return", riskReturn));

        // 输出指标（FR-007）
        ctx.output("kpi_dashboard", "综合KPI仪表盘", "gauge", kpis, "%");
        ctx.output("step_decisions", "各步骤决策汇总", "compare", decisions, null);
        ctx.output("total_chain_cost", "全链路成本", "scalar", round2(cost), "万元");
        ctx.output("risk_return", "风险-收益分析", "compare", riskReturn, null);
    }
}
