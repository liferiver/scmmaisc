package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.boolParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 供应链战略联盟伙伴选择仿真执行器（T057，CH6-001）。
 * 模型：企业自评五维能力（研发/采购/制造/物流/营销）→ 生成候选伙伴池（seed 确定性抽样）→
 * 四维评分（资源互补/文化兼容/战略一致/信任，权重和=1）排序 → 联盟契合度 + 能力互补雷达 +
 * 联盟风险（竞争强度/合作历史修正）。
 */
@Component
public class AlliancePartnerExecutor implements ScenarioExecutor {

    private static final String[] DIMENSIONS = {"研发", "采购", "制造", "物流", "营销"};
    private static final String[] SELF_KEYS =
            {"self_rd", "self_procurement", "self_manufacturing", "self_logistics", "self_marketing"};

    @Override
    public String engineKey() {
        return "alliance-partner";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        for (String key : SELF_KEYS) {
            doubleParam(params, key, 0, 10, errors);
        }
        Integer count = intParam(params, "candidate_count", 5, 20, errors);
        Double wComp = doubleParam(params, "weight_complement", 0, 1, errors);
        Double wCul = doubleParam(params, "weight_culture", 0, 1, errors);
        Double wStr = doubleParam(params, "weight_strategy", 0, 1, errors);
        Double wTrust = doubleParam(params, "weight_trust", 0, 1, errors);
        Double competition = doubleParam(params, "competition_intensity", 0, 1, errors);
        Boolean history = boolParam(params, "has_history", errors);
        if (errors.isEmpty() && count != null && wComp != null && wCul != null
                && wStr != null && wTrust != null && competition != null && history != null) {
            // 约束 weight_sum：四维评估权重之和 = 1
            if (Math.abs(wComp + wCul + wStr + wTrust - 1.0) > 0.01) {
                errors.add("weight_sum 约束不满足：评估维度权重之和需等于 1（当前 "
                        + round2(wComp + wCul + wStr + wTrust) + "）");
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
        double[] self = new double[5];
        for (int i = 0; i < 5; i++) {
            self[i] = ((Number) params.get(SELF_KEYS[i])).doubleValue();
        }
        int count = ((Number) params.get("candidate_count")).intValue();
        double wComp = ((Number) params.get("weight_complement")).doubleValue();
        double wCul = ((Number) params.get("weight_culture")).doubleValue();
        double wStr = ((Number) params.get("weight_strategy")).doubleValue();
        double wTrust = ((Number) params.get("weight_trust")).doubleValue();
        double competition = ((Number) params.get("competition_intensity")).doubleValue();
        boolean history = (Boolean) params.get("has_history");

        // 步骤 1：自我评估（识别核心竞争力）
        int core = 0;
        for (int i = 1; i < 5; i++) {
            if (self[i] > self[core]) {
                core = i;
            }
        }
        ctx.step(String.format("自评能力：研发 %.1f / 采购 %.1f / 制造 %.1f / 物流 %.1f / 营销 %.1f；核心竞争力 = %s",
                        self[0], self[1], self[2], self[3], self[4], DIMENSIONS[core]),
                Map.of("core_dimension", DIMENSIONS[core]));

        // 步骤 2-3：候选池生成（seed 确定性）与多维评分排序
        List<double[]> candidates = new ArrayList<>(); // [comp, culture, strategy, trust, ...cap]
        List<double[]> caps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double[] cap = new double[5];
            double complementSum = 0;
            for (int d = 0; d < 5; d++) {
                cap[d] = 3 + ctx.random().nextDouble() * 7;
                complementSum += Math.max(0, (cap[d] - self[d]) / 10.0);
            }
            double comp = complementSum / 5;
            double culture = 0.55 + ctx.random().nextDouble() * 0.4;
            double strategy = 0.50 + ctx.random().nextDouble() * 0.45;
            double trust = 0.45 + ctx.random().nextDouble() * 0.4;
            if (history) {
                trust = Math.min(0.98, trust + 0.15);
            }
            candidates.add(new double[]{comp, culture, strategy, trust});
            caps.add(cap);
        }
        List<Map<String, Object>> ranking = new ArrayList<>();
        int best = 0;
        double bestScore = -1;
        for (int i = 0; i < count; i++) {
            double[] c = candidates.get(i);
            double score = wComp * c[0] + wCul * c[1] + wStr * c[2] + wTrust * c[3];
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
            ranking.add(Map.of("name", "伙伴" + (char) ('A' + i), "value", round2(score * 100)));
        }
        ranking.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> ((Number) m.get("value")).doubleValue()).reversed());
        ctx.step(String.format("候选 %d 家按四维评分排序（互补%.0f%%/文化%.0f%%/战略%.0f%%/信任%.0f%%）",
                        count, wComp * 100, wCul * 100, wStr * 100, wTrust * 100),
                Map.of("partner_ranking", ranking));

        // 步骤 4：契合度、互补雷达与联盟风险
        double fit = bestScore * 100;
        List<Map<String, Object>> radar = new ArrayList<>();
        for (int d = 0; d < 5; d++) {
            radar.add(Map.of("name", DIMENSIONS[d],
                    "value", round2(Math.max(0, caps.get(best)[d] - self[d]))));
        }
        double trust = candidates.get(best)[3];
        double risk = Math.min(100, (1 - fit / 100) * 55 + competition * 40
                + (history ? 0 : 12) - trust * 10);
        ctx.step(String.format("最优伙伴：伙伴%s，契合度 %.1f；联盟风险 %.1f（竞争强度 %.0f%%，%s）",
                        (char) ('A' + best), fit, risk, competition * 100,
                        history ? "有合作基础" : "无合作基础"),
                Map.of("best_partner", "伙伴" + (char) ('A' + best), "fit_score", round2(fit),
                        "alliance_risk", round2(risk), "complement_radar", radar));

        // 输出指标（FR-007）
        ctx.output("partner_ranking", "候选伙伴排名", "compare", ranking, null);
        ctx.output("fit_score", "联盟契合度评分", "scalar", round2(fit), null);
        ctx.output("complement_radar", "能力互补雷达图", "compare", radar, null);
        ctx.output("alliance_risk", "联盟风险评分", "gauge", round2(risk), null);
    }
}
