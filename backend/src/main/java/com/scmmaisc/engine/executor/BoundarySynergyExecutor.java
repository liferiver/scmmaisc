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
 * 物流管理与供应链管理边界与协同仿真执行器（T052，CH1-005）。
 * 模型：企业类型（制造/零售/电商）设定物流职责边界 → 物流功能 × SCM 职能重叠度矩阵
 * （物流覆盖面越大、协同成熟度越低 → 重叠度越高）→ 协同效率评估 → 边界模糊成本测算。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class BoundarySynergyExecutor implements ScenarioExecutor {

    private static final Set<String> ENTERPRISE_TYPES = Set.of("manufacturing", "retail", "ecommerce");
    private static final List<String> LOGISTICS_FUNCTIONS = List.of("运输", "仓储", "配送", "包装", "信息管理");
    private static final List<String> SCM_FUNCTIONS = List.of("采购", "计划", "库存", "协同", "金融");

    /** 基准重叠度：物流功能 × SCM 职能（0-100） */
    private static final double[][] BASE_OVERLAP = {
            {60, 50, 70, 40, 30},
            {80, 60, 80, 50, 20},
            {70, 40, 50, 60, 40},
            {40, 30, 30, 70, 50},
            {50, 60, 40, 60, 70}};

    @Override
    public String engineKey() {
        return "boundary-synergy";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        enumParam(params, "enterprise_type", ENTERPRISE_TYPES, errors);
        intParam(params, "scm_levels", 2, 5, errors);
        doubleParam(params, "logistics_coverage", 0.3, 1.0, errors);
        intParam(params, "synergy_maturity", 1, 5, errors);
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        String enterprise = String.valueOf(params.get("enterprise_type"));
        int scmLevels = ((Number) params.get("scm_levels")).intValue();
        double coverage = ((Number) params.get("logistics_coverage")).doubleValue();
        int maturity = ((Number) params.get("synergy_maturity")).intValue();
        String enterpriseName = switch (enterprise) {
            case "retail" -> "零售";
            case "ecommerce" -> "电商";
            default -> "制造";
        };
        // 企业类型对物流职责边界的影响系数（电商物流即核心业务 → 边界更模糊）
        double boundaryBase = switch (enterprise) {
            case "retail" -> 0.95;
            case "ecommerce" -> 1.1;
            default -> 1.0;
        };

        // 步骤 1：企业场景与边界设定
        ctx.step(String.format("企业场景：%s企业，供应链层级 %d 级，物流功能覆盖面 %.0f%%，协同机制成熟度 %d/5",
                enterpriseName, scmLevels, coverage * 100, maturity),
                Map.of("enterprise_type", enterprise, "scm_levels", scmLevels,
                        "logistics_coverage", round2(coverage), "synergy_maturity", maturity));

        // 步骤 2：物流-SCM 功能重叠度矩阵（协同越成熟 → 重叠成本越低）
        List<List<Double>> data = new ArrayList<>();
        for (int r = 0; r < LOGISTICS_FUNCTIONS.size(); r++) {
            List<Double> row = new ArrayList<>();
            for (int c = 0; c < SCM_FUNCTIONS.size(); c++) {
                double overlap = BASE_OVERLAP[r][c] * coverage * boundaryBase
                        * (1 - 0.5 * maturity / 5.0);
                row.add(round2(Math.min(100, overlap)));
            }
            data.add(row);
        }
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", LOGISTICS_FUNCTIONS);
        heatmap.put("columns", SCM_FUNCTIONS);
        heatmap.put("data", data);
        double avgOverlap = data.stream().flatMap(List::stream).mapToDouble(Double::doubleValue).average().orElse(0);
        ctx.step(String.format("功能重叠度矩阵完成：平均重叠度 %.0f%%（覆盖面 %.0f%%、协同成熟度 %d/5 降低重叠成本）",
                avgOverlap, coverage * 100, maturity), Map.of("avg_overlap", round2(avgOverlap)));

        // 步骤 3：协同效率评估（覆盖贡献 + 机制贡献，企业类型微调）
        double efficiency = Math.min(100, (coverage * 60 + maturity / 5.0 * 40) * (boundaryBase < 1 ? 1.05 : boundaryBase));
        ctx.step(String.format("协同效率 %.0f%%（物流覆盖贡献 %.0f%% + 协同机制贡献 %.0f%%）",
                efficiency, coverage * 60, maturity / 5.0 * 40),
                Map.of("synergy_efficiency", round2(efficiency)));

        // 步骤 4：边界模糊成本
        double boundaryCost = 800 * (1 - 0.6 * maturity / 5.0) * (1.2 - coverage) * boundaryBase;
        ctx.step(String.format("边界模糊成本 %.0f 万元/年（协同成熟度提升 1 级约降低 %.0f 万元）",
                boundaryCost, 800 * 0.12 * (1.2 - coverage) * boundaryBase),
                Map.of("boundary_cost", round2(boundaryCost)));

        // 输出指标（FR-007）
        ctx.output("overlap_heatmap", "物流-SCM功能重叠度矩阵", "heatmap", heatmap, null);
        ctx.output("synergy_efficiency", "协同效率", "scalar", round2(efficiency), "%");
        ctx.output("boundary_cost", "边界模糊导致的成本", "scalar", round2(boundaryCost), "万元");
    }
}
