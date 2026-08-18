package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 供应链模块化设计仿真执行器（T058，CH7-007）。
 * 模型：一体化设计（每变体独立组件，安全库存 1.65σ 逐件计算）vs 模块化设计
 * （通用平台件 + 专用模块，通用件需求合并 → 风险汇聚 σ√N 缩减）→ 库存/缺货率/成本/
 * 上市时间对比。默认：30 变体、10 通用件 + 5 专用件、CV 0.3 → 库存下降约 54%。
 */
@Component
public class ModularityExecutor implements ScenarioExecutor {

    private static final double MEAN_DEMAND = 1000.0; // 每变体年需求（件）
    private static final double UNIT_COST = 100.0;    // 组件成本（元/件）
    private static final double ASSEMBLY_BASE = 150_000.0; // 每变体组装开发成本（元）

    @Override
    public String engineKey() {
        return "modularity";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "variant_count", 10, 100, errors);
        Integer common = intParam(params, "common_modules", 5, 20, errors);
        intParam(params, "dedicated_modules", 3, 10, errors);
        doubleParam(params, "demand_cv", 0.1, 0.6, errors);
        doubleParam(params, "common_cost_premium", 0.05, 0.2, errors);
        doubleParam(params, "assembly_time_ratio", 0.5, 0.8, errors);
        if (errors.isEmpty() && common != null && common < 5) {
            errors.add("interface_ok 约束不满足：通用组件数需 ≥ 5 以形成平台效应");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    /** 标准正态 CDF 近似（Bowler 公式，z≥0 误差 <0.003）。 */
    private static double normCdf(double z) {
        if (z < 0) {
            return 1 - normCdf(-z);
        }
        if (z > 6) {
            return 1.0;
        }
        return 1 - 0.5 * Math.exp(-0.717 * z - 0.416 * z * z);
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int variants = ((Number) params.get("variant_count")).intValue();
        int common = ((Number) params.get("common_modules")).intValue();
        int dedicated = ((Number) params.get("dedicated_modules")).intValue();
        double cv = ((Number) params.get("demand_cv")).doubleValue();
        double premium = ((Number) params.get("common_cost_premium")).doubleValue();
        double atr = ((Number) params.get("assembly_time_ratio")).doubleValue();

        // 步骤 1：产品族与需求参数
        double sigma = cv * MEAN_DEMAND;
        ctx.step(String.format("产品族：%d 个变体；需求 CV %.2f（σ=%.0f 件/年/变体）",
                        variants, cv, sigma),
                Map.of("variant_count", variants, "demand_cv", cv));

        // 步骤 2：一体化设计（每变体独立组件，逐件安全库存 1.65σ）
        int partsIntegrated = variants * (common + dedicated);
        double ssEach = 1.65 * sigma;
        double invIntegrated = partsIntegrated * ssEach;
        double assemblyI = variants * ASSEMBLY_BASE;
        double costIntegrated = invIntegrated * UNIT_COST + assemblyI;
        ctx.step(String.format("一体化设计：%d 个独立组件 × 安全库存 %.0f 件 = %,.0f 件（缺货率 ≈ 5%%）",
                        partsIntegrated, ssEach, invIntegrated),
                Map.of("integrated_inventory", round2(invIntegrated)));

        // 步骤 3：模块化设计（通用件需求合并风险汇聚 σ→σ√N；专用件逐变体）
        double ssCommon = 1.65 * sigma * Math.sqrt(variants);
        double invModular = common * ssCommon + variants * dedicated * ssEach;
        double zPool = 1.65 * Math.sqrt(variants);
        double stockout = (1 - normCdf(zPool)) * 100;
        double assemblyM = variants * ASSEMBLY_BASE * atr * 1.05;
        double costModular = (common * ssCommon * (1 + premium) + variants * dedicated * ssEach)
                * UNIT_COST + assemblyM;
        double pooling = (1 - invModular / invIntegrated) * 100;
        ctx.step(String.format("模块化设计：%d 通用件（汇聚后 σ×√%d=%.0f）+ %d 专用件 → %,.0f 件，缺货率 %.3f%%",
                        common, variants, ssCommon, dedicated, invModular, stockout),
                Map.of("modular_inventory", round2(invModular), "stockout_rate", round2(stockout)));

        // 步骤 4：成本与上市时间对比
        double timeToMarket = 180 * atr;
        List<Map<String, Object>> costCompare = List.of(
                Map.of("name", "一体化库存成本", "value", round2(invIntegrated * UNIT_COST)),
                Map.of("name", "模块化库存成本", "value", round2((common * ssCommon * (1 + premium)
                        + variants * dedicated * ssEach) * UNIT_COST)),
                Map.of("name", "一体化总成本", "value", round2(costIntegrated)),
                Map.of("name", "模块化总成本", "value", round2(costModular)));
        ctx.step(String.format("总成本：一体化 %,.0f 元 vs 模块化 %,.0f 元（节省 %,.1f%%）；上市时间 %.0f 天",
                        costIntegrated, costModular, (1 - costModular / costIntegrated) * 100, timeToMarket),
                Map.of("cost_compare", costCompare, "time_to_market", round2(timeToMarket)));

        // 步骤 5：风险汇聚效应与汇总
        ctx.step(String.format("风险汇聚效应：安全库存下降 %,.1f%%（库存 %,.0f → %,.0f 件）",
                        pooling, invIntegrated, invModular),
                Map.of("risk_pooling", round2(pooling), "total_inventory", round2(invModular)));

        // 输出指标（FR-007）
        ctx.output("total_inventory", "库存总水平", "scalar", round2(invModular), "件");
        ctx.output("stockout_rate", "缺货率", "scalar", round2(stockout), "%");
        ctx.output("cost_compare", "总成本对比", "compare", costCompare, "元");
        ctx.output("time_to_market", "新产品上市时间", "scalar", round2(timeToMarket), "天");
        ctx.output("risk_pooling", "风险汇聚效应", "scalar", round2(pooling), "%");
    }
}
