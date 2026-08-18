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
import static com.scmmaisc.engine.executor.ExecutorSupport.matrixParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 供应链金融风险识别与评估仿真执行器（T060，CH9-005）。
 * 模型：构建多客户多模式业务组合 → 四类风险事件（法律/市场/信用/操作）概率评估 → 期望损失
 * EL=ΣE×PD×LGD、非预期损失 UL=√ΣE²×PD×(1-PD)×LGD → Monte Carlo 1000 路径计算 VaR(99%)
 * （违约相关性驱动的传染）→ 四档压力测试（基准/不利/严重/极端）→ 缓释工具降低 LGD →
 * 风险覆盖倍数（资本缓冲/VaR）与风险传染网络。
 */
@Component
public class FinanceRiskExecutor implements ScenarioExecutor {

    private static final int MC_PATHS = 1000;            // VaR 蒙特卡洛路径数
    private static final double CAPITAL_MULTIPLE = 4.0;  // 资本缓冲 = 4×EL（银行拨备）
    private static final String[] EVENT_NAMES = {"法律", "市场", "信用", "操作"};
    private static final String[] MODE_NAMES = {"应收账款", "预付账款", "存货质押"};

    @Override
    public String engineKey() {
        return "finance-risk";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "portfolio_size", 5, 50, errors);
        if (params.get("risk_matrix") != null) {
            matrixParam(params, "risk_matrix", 4, 4, 4, 0.0, 1.0, errors);
        }
        doubleParam(params, "lgd", 0.3, 0.8, errors);
        doubleParam(params, "default_correlation", 0.1, 0.6, errors);
        enumParam(params, "stress_scenario", Set.of("baseline", "adverse", "severe", "extreme"), errors);
        enumParam(params, "risk_mitigation", Set.of("margin", "guarantee", "insurance", "hedging"), errors);
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int size = ((Number) params.get("portfolio_size")).intValue();
        double lgd = ((Number) params.get("lgd")).doubleValue();
        double rho = ((Number) params.get("default_correlation")).doubleValue();
        String stress = String.valueOf(params.get("stress_scenario"));
        String mitigation = String.valueOf(params.get("risk_mitigation"));

        // 步骤 1：业务组合构建（多客户 × 多模式，seed 确定性）
        double[] exposure = new double[size];   // 万元
        double[] pd = new double[size];
        int[] mode = new int[size];
        double[][] riskMatrix = params.get("risk_matrix") != null
                ? matrixParam(params, "risk_matrix", 4, 4, 4, 0.0, 1.0, new ArrayList<>())
                : null;
        double[] eventProb = riskMatrix != null
                ? new double[]{riskMatrix[0][0], riskMatrix[1][0], riskMatrix[2][0], riskMatrix[3][0]}
                : new double[]{0.08, 0.15, 0.12, 0.05};   // 法律/市场/信用/操作缺省概率
        double totalExposure = 0;
        for (int i = 0; i < size; i++) {
            exposure[i] = 100 + ctx.random().nextDouble() * 400;   // 100-500 万元
            mode[i] = i % 3;
            double eventP = eventProb[mode[i]];                    // 按业务模式关联风险事件
            pd[i] = Math.max(0.02, Math.min(0.3, eventP * (1 + rho * ctx.random().nextGaussian() * 0.5)));
            totalExposure += exposure[i];
        }
        ctx.step(String.format("业务组合：%d 户客户（应收 %d / 预付 %d / 存货 %d），"
                        + "总敞口 %,.0f 万元；风险事件概率：法律 %.0f%% / 市场 %.0f%% / 信用 %.0f%% / 操作 %.0f%%",
                size, (int) java.util.Arrays.stream(mode).filter(m -> m == 0).count(),
                (int) java.util.Arrays.stream(mode).filter(m -> m == 1).count(),
                (int) java.util.Arrays.stream(mode).filter(m -> m == 2).count(),
                totalExposure, eventProb[0] * 100, eventProb[1] * 100, eventProb[2] * 100, eventProb[3] * 100),
                Map.of("portfolio_size", size, "total_exposure", round2(totalExposure)));

        // 步骤 2：EL / UL / VaR(99%) 计算
        double el = 0, ul2 = 0;
        for (int i = 0; i < size; i++) {
            el += exposure[i] * pd[i] * lgd;
            ul2 += exposure[i] * exposure[i] * pd[i] * (1 - pd[i]) * lgd * lgd;
        }
        double ul = Math.sqrt(ul2);
        // Monte Carlo：系统性因子 z 驱动传染（条件违约概率上移）
        List<Double> losses = new ArrayList<>();
        for (int path = 0; path < MC_PATHS; path++) {
            double z = ctx.random().nextGaussian();
            double loss = 0;
            for (int i = 0; i < size; i++) {
                double pCond = Math.max(0.0, Math.min(1.0, pd[i] + rho * z * Math.sqrt(pd[i] * (1 - pd[i]))));
                if (ctx.random().nextDouble() < pCond) {
                    loss += exposure[i] * lgd;
                }
            }
            losses.add(loss);
        }
        losses.sort(Double::compareTo);
        double var99 = losses.get((int) (MC_PATHS * 0.99) - 1);
        ctx.step(String.format("风险度量：EL %,.0f 万元 / UL %,.0f 万元 / VaR(99%%) %,.0f 万元"
                        + "（%d 条路径，相关性 ρ=%.1f）",
                el, ul, var99, MC_PATHS, rho),
                Map.of("expected_loss", round2(el), "unexpected_loss", round2(ul), "var_99", round2(var99)));

        // 步骤 3：四档压力测试（损失乘数）
        double mult = switch (stress) {
            case "baseline" -> 1.0;
            case "adverse" -> 1.5;
            case "severe" -> 2.5;
            default -> 4.0;
        };
        List<Map<String, Object>> stressLoss = List.of(
                Map.of("name", "基准", "value", round2(el * 1.0)),
                Map.of("name", "不利", "value", round2(el * 1.5)),
                Map.of("name", "严重", "value", round2(el * 2.5)),
                Map.of("name", "极端", "value", round2(el * 4.0)));
        ctx.step(String.format("压力测试（当前情景：%s，损失乘数 %.1f）：极端情景损失 %,.0f 万元 ≈ %.1f×EL",
                stress, mult, el * 4.0, 4.0),
                Map.of("stress_loss_compare", stressLoss, "stress_multiplier", round2(mult)));

        // 步骤 4：缓释工具与风险覆盖倍数（资本缓冲/VaR）
        double mitigationFactor = switch (mitigation) {
            case "margin" -> 0.5;        // 保证金
            case "guarantee" -> 0.65;    // 担保
            case "insurance" -> 0.7;     // 保险
            default -> 0.8;              // 对冲
        };
        double lgdEff = lgd * mitigationFactor;
        double elM = 0, ulM2 = 0;
        for (int i = 0; i < size; i++) {
            elM += exposure[i] * pd[i] * lgdEff;
            ulM2 += exposure[i] * exposure[i] * pd[i] * (1 - pd[i]) * lgdEff * lgdEff;
        }
        double ulM = Math.sqrt(ulM2);
        double varM = elM + 2.33 * ulM;
        double capital = CAPITAL_MULTIPLE * elM;
        double coverage = capital / varM;
        ctx.step(String.format("缓释工具【%s】降低 LGD 至 %.1f%%：EL %,.0f 万 / VaR %,.0f 万；"
                        + "资本缓冲 %,.0f 万 → 覆盖倍数 %.2f 倍",
                mitigation, lgdEff * 100, elM, varM, capital, coverage),
                Map.of("coverage_ratio", round2(coverage), "mitigation_lgd", round2(lgdEff * 100)));

        // 步骤 5：风险传染网络（2014 钢贸重复质押风险传导链）
        Map<String, Object> topo = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = List.of(
                Map.of("id", "core", "name", "核心企业", "type", "core"),
                Map.of("id", "e1", "name", "法律-重复质押", "type", "risk"),
                Map.of("id", "e2", "name", "市场-价格暴跌", "type", "risk"),
                Map.of("id", "e3", "name", "信用-评级降级", "type", "risk"),
                Map.of("id", "e4", "name", "操作-监管疏忽", "type", "risk"),
                Map.of("id", "c1", "name", "客户A", "type", "customer"),
                Map.of("id", "c2", "name", "客户B", "type", "customer"),
                Map.of("id", "c3", "name", "客户C", "type", "customer"));
        topo.put("nodes", nodes);
        topo.put("edges", List.of(
                Map.of("source", "e1", "target", "c1"),
                Map.of("source", "e1", "target", "c2"),
                Map.of("source", "e2", "target", "c2"),
                Map.of("source", "e2", "target", "c3"),
                Map.of("source", "e3", "target", "core"),
                Map.of("source", "core", "target", "c1"),
                Map.of("source", "core", "target", "c2"),
                Map.of("source", "core", "target", "c3"),
                Map.of("source", "e4", "target", "c1")));
        ctx.step("传染网络：2014 钢贸案例中重复质押（法律）+ 钢价暴跌（市场）沿担保链传染，"
                        + "核心企业评级降级加剧信用风险", Map.of("contagion_network", topo));

        // 输出指标（FR-007）
        ctx.output("expected_loss", "期望损失EL", "scalar", round2(el), "元");
        ctx.output("unexpected_loss", "非预期损失UL", "scalar", round2(ul), "元");
        ctx.output("var_99", "风险价值VaR(99%)", "scalar", round2(var99), "元");
        ctx.output("stress_loss_compare", "压力测试损失", "compare", stressLoss, "元");
        ctx.output("coverage_ratio", "风险覆盖倍数", "gauge", List.of(
                Map.of("name", "风险覆盖倍数", "value", round2(coverage))), "倍");
        ctx.output("contagion_network", "风险传染网络", "topo", topo, null);
    }
}
