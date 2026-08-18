package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.RandomSource;
import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.boolParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 牛鞭效应抑制策略量化仿真执行器（T059，CH8-002）。
 * 模型：啤酒游戏 L 级链（零售商→批发商→分销商→工厂）订单-库存-缺货仿真，需求第 6 轮跳变；
 * 对照实验：基线（传统模式）vs 四类单对策（信息共享/缩短提前期/小批量高频/VMI）vs 综合对策
 * （当前开关组合）→ 牛鞭效应指数 BE=σ(订单)/σ(需求) 逐级放大、总成本与单对策贡献度、信息共享 ROI。
 */
@Component
public class BullwhipMitigationExecutor implements ScenarioExecutor {

    private static final double INFO_SHARE_COST = 2000.0; // 信息共享系统年化成本（元）
    private static final double BASIC_RATE = 10.0;         // 单位生产成本（元/箱）

    @Override
    public String engineKey() {
        return "bullwhip-mitigation";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "levels", 3, 5, errors);
        intParam(params, "initial_demand", 1, 20, errors);
        intParam(params, "demand_jump", 1, 100, errors);
        intParam(params, "lead_time", 1, 3, errors);
        doubleParam(params, "holding_cost", 0.5, 2, errors);
        doubleParam(params, "stockout_cost", 2, 10, errors);
        intParam(params, "total_rounds", 30, 50, errors);
        Boolean info = boolParam(params, "info_share", errors);
        Boolean lead = boolParam(params, "short_lead_time", errors);
        Boolean batch = boolParam(params, "small_batch", errors);
        Boolean vmi = boolParam(params, "vmi", errors);
        if (errors.isEmpty() && info != null && lead != null && batch != null && vmi != null
                && !info && !lead && !batch && !vmi) {
            errors.add("be_reduced 约束不满足：至少启用一种对策以观察 BE 抑制效果（建议逐一实验再综合）");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 单次链仿真：返回 {牛鞭效应指数(顶层订单), 总成本}。 */
    private static double[] runChain(int levels, int rounds, double init, double jump, int lead,
                                     boolean infoShare, boolean shortLead, boolean smallBatch, boolean vmi,
                                     double holdCost, double stockoutCost, RandomSource rnd) {
        int[] leadT = new int[levels];
        for (int i = 0; i < levels; i++) {
            leadT[i] = shortLead ? Math.max(1, (lead + 1) / 2) : lead;
        }
        double[] inv = new double[levels];
        double[] backlog = new double[levels];
        @SuppressWarnings("unchecked")
        Deque<Double>[] pipeline = new Deque[levels];
        for (int i = 0; i < levels; i++) {
            pipeline[i] = new ArrayDeque<>();
        }
        double[] order = new double[levels];
        double[] orderSum = new double[levels];
        double[] orderSumSq = new double[levels];
        double[] dSeries = new double[rounds];
        double cost = 0;
        for (int t = 0; t < rounds; t++) {
            double d = (t < 5 ? init : jump) * (0.9 + rnd.nextDouble() * 0.2);
            dSeries[t] = d;
            for (int i = 0; i < levels; i++) {
                double arrives = pipeline[i].isEmpty() ? 0 : pipeline[i].poll();
                inv[i] += arrives;
                double dmd = (i == 0) ? d : order[i - 1];
                double fill = Math.min(inv[i], dmd + backlog[i]);
                inv[i] -= fill;
                backlog[i] = dmd + backlog[i] - fill;
                // 订货策略：基线库存 = (提前期+1)×所见需求；信息共享 → 上游可见真实消费需求
                double seen = (infoShare && i > 0) ? d : dmd;
                double target = (leadT[i] + 1) * seen;
                double desired = Math.max(0, seen + (target - inv[i] - pipeline[i].size() * seen) - backlog[i]);
                if (smallBatch) {
                    double batch = 4.0;
                    desired = Math.ceil(desired / batch) * batch;
                    desired = 0.5 * desired + 0.5 * order[i];
                }
                if (vmi && i < levels - 1) {
                    desired = dmd; // 供应商管理库存：按下游需求直补，无放大
                }
                order[i] = desired;
                if (i < levels - 1) {
                    pipeline[i + 1].add(desired);
                }
                orderSum[i] += desired;
                orderSumSq[i] += desired * desired;
                cost += inv[i] * holdCost + backlog[i] * stockoutCost;
            }
        }
        double dMean = mean(dSeries);
        double dStd = std(dSeries, dMean);
        int top = levels - 1;
        double oMean = orderSum[top] / rounds;
        double oStd = Math.sqrt(Math.max(0, orderSumSq[top] / rounds - oMean * oMean));
        return new double[]{oStd / Math.max(1e-9, dStd), cost + levels * rounds * BASIC_RATE * 0.01};
    }

    private static double mean(double[] arr) {
        double s = 0;
        for (double v : arr) {
            s += v;
        }
        return s / arr.length;
    }

    private static double std(double[] arr, double m) {
        double s = 0;
        for (double v : arr) {
            s += (v - m) * (v - m);
        }
        return Math.sqrt(s / arr.length);
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int levels = ((Number) params.get("levels")).intValue();
        double init = ((Number) params.get("initial_demand")).doubleValue();
        double jump = ((Number) params.get("demand_jump")).doubleValue();
        int lead = ((Number) params.get("lead_time")).intValue();
        double holdCost = ((Number) params.get("holding_cost")).doubleValue();
        double stockoutCost = ((Number) params.get("stockout_cost")).doubleValue();
        int rounds = ((Number) params.get("total_rounds")).intValue();
        boolean infoShare = (Boolean) params.get("info_share");
        boolean shortLead = (Boolean) params.get("short_lead_time");
        boolean smallBatch = (Boolean) params.get("small_batch");
        boolean vmi = (Boolean) params.get("vmi");
        String[] levelNames = {"零售商", "批发商", "分销商", "工厂", "总厂"};

        // 步骤 1：基线仿真（传统模式）
        double[] base = runChain(levels, rounds, init, jump, lead, false, false, false, false,
                holdCost, stockoutCost, ctx.random());
        ctx.step(String.format("基线（传统模式）：%d 级链 %d 轮，牛鞭效应指数 BE=%.2f，总成本 %,.0f 元",
                        levels, rounds, base[0], base[1]),
                Map.of("baseline_be", round2(base[0]), "baseline_cost", round2(base[1])));

        // 步骤 2：单对策对照实验（B 信息共享 / C 缩短提前期 / D 小批量 / E VMI）
        double[] b = runChain(levels, rounds, init, jump, lead, true, false, false, false,
                holdCost, stockoutCost, ctx.random());
        double[] c = runChain(levels, rounds, init, jump, lead, false, true, false, false,
                holdCost, stockoutCost, ctx.random());
        double[] d = runChain(levels, rounds, init, jump, lead, false, false, true, false,
                holdCost, stockoutCost, ctx.random());
        double[] e = runChain(levels, rounds, init, jump, lead, false, false, false, true,
                holdCost, stockoutCost, ctx.random());
        ctx.step(String.format("单对策：信息共享 BE=%.2f / 缩短提前期 BE=%.2f / 小批量 BE=%.2f / VMI BE=%.2f",
                        b[0], c[0], d[0], e[0]),
                Map.of("info_share_be", round2(b[0]), "short_lead_be", round2(c[0]),
                        "small_batch_be", round2(d[0]), "vmi_be", round2(e[0])));

        // 步骤 3：综合对策（当前开关组合）
        double[] f = runChain(levels, rounds, init, jump, lead, infoShare, shortLead, smallBatch, vmi,
                holdCost, stockoutCost, ctx.random());
        ctx.step(String.format("当前配置（信息共享=%s/短提前期=%s/小批量=%s/VMI=%s）：BE=%.2f，总成本 %,.0f 元",
                        infoShare, shortLead, smallBatch, vmi, f[0], f[1]),
                Map.of("combined_be", round2(f[0]), "combined_cost", round2(f[1])));

        // 步骤 4：BE/成本对比与对策贡献分析
        List<Map<String, Object>> beCompare = List.of(
                Map.of("name", "基线", "value", round2(base[0])),
                Map.of("name", "信息共享", "value", round2(b[0])),
                Map.of("name", "缩短提前期", "value", round2(c[0])),
                Map.of("name", "小批量", "value", round2(d[0])),
                Map.of("name", "VMI", "value", round2(e[0])),
                Map.of("name", "当前配置", "value", round2(f[0])));
        List<Map<String, Object>> costCompare = List.of(
                Map.of("name", "基线", "value", round2(base[1])),
                Map.of("name", "信息共享", "value", round2(b[1])),
                Map.of("name", "缩短提前期", "value", round2(c[1])),
                Map.of("name", "小批量", "value", round2(d[1])),
                Map.of("name", "VMI", "value", round2(e[1])),
                Map.of("name", "当前配置", "value", round2(f[1])));
        List<Map<String, Object>> contribution = List.of(
                Map.of("name", "信息共享", "value", round2((base[0] - b[0]) / base[0] * 100)),
                Map.of("name", "缩短提前期", "value", round2((base[0] - c[0]) / base[0] * 100)),
                Map.of("name", "小批量", "value", round2((base[0] - d[0]) / base[0] * 100)),
                Map.of("name", "VMI", "value", round2((base[0] - e[0]) / base[0] * 100)),
                Map.of("name", "综合对策", "value", round2((base[0] - f[0]) / base[0] * 100)));
        ctx.step("各对策对牛鞭效应的抑制贡献（相对基线的 BE 降幅）", Map.of("contribution_analysis", contribution));

        // 步骤 5：信息共享 ROI 与汇总
        double roi = (base[1] - b[1]) / INFO_SHARE_COST * 100;
        ctx.step(String.format("信息共享 ROI %.1f%%（节省 %,.0f 元 / 投入 %,.0f 元）",
                        roi, base[1] - b[1], INFO_SHARE_COST),
                Map.of("info_share_roi", round2(roi)));
        ctx.info(String.format("牛鞭效应指数：%s→%s 级逐级放大，BE=%.2f（>1 表示订单波动被放大）",
                levelNames[0], levelNames[levels - 1], f[0]));

        // 输出指标（FR-007）
        ctx.output("be_compare", "各策略BE抑制效果对比", "compare", beCompare, null);
        ctx.output("cost_compare", "总成本对比(各对策)", "compare", costCompare, "元");
        ctx.output("contribution_analysis", "对策贡献分析(降低BE%)", "compare", contribution, "%");
        ctx.output("info_share_roi", "信息共享ROI", "scalar", round2(roi), "%");
    }
}
