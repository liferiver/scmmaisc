package com.scmmaisc.engine.executor;

import com.scmmaisc.engine.ScenarioExecutor;
import com.scmmaisc.engine.SimContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.scmmaisc.engine.executor.ExecutorSupport.doubleParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.intParam;
import static com.scmmaisc.engine.executor.ExecutorSupport.round2;

/**
 * 库存管理综合对比仿真执行器（T053，CH2-010；组合 CH2-003~006 四个子模型）。
 * 模型：同一需求场景（D/C2/C1/L/d/p/C3/dist/Q/α/p）下分别计算
 * ① 确定 EOQ ② 非瞬时补货 POQ ③ 允许缺货 EOQ ④ 随机 (s,Q) 的
 * 年总成本、平均库存与服务水平 → 对比 + 决策树推荐 + 适用条件匹配度热图。
 * 确定性模型：seed 无关（FR-008）。
 */
@Component
public class InventoryCompareExecutor implements ScenarioExecutor {

    private static final List<String> MODEL_NAMES = List.of("确定EOQ", "非瞬时补货POQ", "允许缺货EOQ", "随机(s,Q)");

    @Override
    public String engineKey() {
        return "inventory-compare";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "annual_demand", 100, 100000, errors);
        doubleParam(params, "order_cost", 10, 5000, errors);
        doubleParam(params, "holding_cost", 1, 500, errors);
        doubleParam(params, "lead_time", 1, 14, errors);
        Double d = doubleParam(params, "daily_demand", 0.0001, 1000, errors);
        doubleParam(params, "setup_cost", 10, 5000, errors);
        Double p = doubleParam(params, "production_rate", 10, 2000, errors);
        doubleParam(params, "backorder_cost", 3, 5000, errors);
        intParam(params, "order_qty", 50, 5000, errors);
        doubleParam(params, "service_level", 0.90, 0.999, errors);
        doubleParam(params, "stockout_cost", 10, 5000, errors);
        Object raw = params.get("demand_dist");
        if (raw != null && raw instanceof Map<?, ?> group && group.get("distType") != null) {
            String type = String.valueOf(group.get("distType"));
            if ("poisson".equals(type) && group.get("lambda") == null) {
                errors.add("demand_dist.lambda 缺失");
            }
            if ("normal".equals(type) && (group.get("mean") == null || group.get("sigma") == null)) {
                errors.add("demand_dist.mean/sigma 缺失");
            }
        }
        // POQ 子模型约束：p > d
        if (errors.isEmpty() && d != null && p != null && p <= d) {
            errors.add("p_gt_d 约束不满足：production_rate (" + p + ") 必须大于 daily_demand (" + d + ")");
        }
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 4;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        double d = ((Number) params.get("annual_demand")).doubleValue();
        double orderCost = ((Number) params.get("order_cost")).doubleValue();
        double holdingCost = ((Number) params.get("holding_cost")).doubleValue();
        double leadTime = ((Number) params.get("lead_time")).doubleValue();
        double dailyDemand = ((Number) params.get("daily_demand")).doubleValue();
        double setupCost = ((Number) params.get("setup_cost")).doubleValue();
        double productionRate = ((Number) params.get("production_rate")).doubleValue();
        double backorderCost = ((Number) params.get("backorder_cost")).doubleValue();
        double orderQty = ((Number) params.get("order_qty")).doubleValue();
        double serviceLevel = ((Number) params.get("service_level")).doubleValue();
        double stockoutCost = ((Number) params.get("stockout_cost")).doubleValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> dist = (Map<String, Object>) params.get("demand_dist");
        String distType = String.valueOf(dist.get("distType"));
        double mu = "normal".equals(distType) ? ((Number) dist.get("mean")).doubleValue() : ((Number) dist.get("lambda")).doubleValue();
        double sigma = "normal".equals(distType) ? ((Number) dist.get("sigma")).doubleValue() : Math.sqrt(mu);

        // 步骤 1：四个子模型计算
        // ① 确定 EOQ
        double qEoq = Math.sqrt(2 * orderCost * d / holdingCost);
        double tcEoq = orderCost * d / qEoq + holdingCost * qEoq / 2;
        double avgEoq = qEoq / 2;
        // ② 非瞬时补货 POQ
        double ratio = 1 - dailyDemand / productionRate;
        double qPoq = Math.sqrt(2 * setupCost * d / (holdingCost * ratio));
        double tcPoq = holdingCost * qPoq * ratio / 2 + setupCost * d / qPoq;
        double avgPoq = qPoq * ratio / 2;
        // ③ 允许缺货 EOQ
        double qStockout = Math.sqrt(2 * orderCost * d / holdingCost * (holdingCost + backorderCost) / backorderCost);
        double sStar = qStockout * holdingCost / (holdingCost + backorderCost);
        double tcStockout = holdingCost * (qStockout - sStar) * (qStockout - sStar) / (2 * qStockout)
                + orderCost * d / qStockout + backorderCost * sStar * sStar / (2 * qStockout);
        double avgStockout = (qStockout - sStar) / 2;
        // ④ 随机 (s,Q)
        double z = NormalDist.zNormal(serviceLevel);
        double ss = z * sigma * Math.sqrt(leadTime);
        double avgSq = orderQty / 2 + ss;
        double shortagePerCycle = sigma * Math.sqrt(leadTime) * NormalDist.loss(z);
        double annualShortage = d / orderQty * shortagePerCycle;
        double tcSq = holdingCost * avgSq + stockoutCost * annualShortage;
        double[] costs = {tcEoq, tcPoq, tcStockout, tcSq};
        double[] avgs = {avgEoq, avgPoq, avgStockout, avgSq};
        ctx.step(String.format("四模型年总成本：确定EOQ %.0f、POQ %.0f、允许缺货EOQ %.0f、(s,Q) %.0f 元",
                tcEoq, tcPoq, tcStockout, tcSq),
                Map.of("tc_eoq", round2(tcEoq), "tc_poq", round2(tcPoq),
                        "tc_stockout", round2(tcStockout), "tc_sq", round2(tcSq)));

        // 步骤 2：成本与库存水平对比
        List<Map<String, Object>> costItems = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            costItems.add(Map.of("name", MODEL_NAMES.get(i), "value", round2(costs[i])));
        }
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (costs[i] < costs[best]) {
                best = i;
            }
        }
        ctx.step(String.format("成本最低：%s（%.0f 元）—— 允许缺货策略以少量缺货换取持有成本大降",
                MODEL_NAMES.get(best), costs[best]), Map.of("lowest_cost_model", MODEL_NAMES.get(best)));

        // 步骤 3：库存水平曲线（12 个月平均库存对比）与模型选择决策树
        List<Double> months = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            months.add((double) m);
        }
        Map<String, Object> curves = new LinkedHashMap<>();
        curves.put("x", months);
        List<Map<String, Object>> seriesList = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            List<Double> line = new ArrayList<>();
            for (int m = 0; m < 12; m++) {
                line.add(round2(avgs[i]));
            }
            seriesList.add(Map.of("name", MODEL_NAMES.get(i), "data", line));
        }
        curves.put("series", seriesList);
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("nodes", List.of(
                Map.of("id", "root", "name", "需求确定？", "type", "decision"),
                Map.of("id", "yes", "name", "是", "type", "branch"),
                Map.of("id", "no", "name", "否", "type", "branch"),
                Map.of("id", "instant", "name", "补货瞬时？", "type", "decision"),
                Map.of("id", "stockout", "name", "允许缺货？", "type", "decision"),
                Map.of("id", "level", "name", "服务水平高？", "type", "decision"),
                Map.of("id", "m_eoq", "name", "确定EOQ", "type", "model"),
                Map.of("id", "m_poq", "name", "非瞬时补货POQ", "type", "model"),
                Map.of("id", "m_soq", "name", "允许缺货EOQ", "type", "model"),
                Map.of("id", "m_sq", "name", "随机(s,Q)", "type", "model")));
        tree.put("edges", List.of(
                Map.of("source", "root", "target", "yes"),
                Map.of("source", "root", "target", "no"),
                Map.of("source", "yes", "target", "instant"),
                Map.of("source", "instant", "target", "stockout"),
                Map.of("source", "instant", "target", "m_poq"),
                Map.of("source", "stockout", "target", "m_soq"),
                Map.of("source", "stockout", "target", "m_eoq"),
                Map.of("source", "no", "target", "level"),
                Map.of("source", "level", "target", "m_sq"),
                Map.of("source", "level", "target", "m_soq")));
        ctx.step("库存曲线对比：确定模型平均库存最低、(s,Q) 因安全库存最高；决策树按 需求确定→补货瞬时→缺货策略→服务水平 推荐模型",
                Map.of("avg_eoq", round2(avgEoq), "avg_poq", round2(avgPoq),
                        "avg_stockout", round2(avgStockout), "avg_sq", round2(avgSq)));

        // 步骤 4：适用条件匹配度热图
        double[][] fit = {
                {90, 10, 20, 60},   // 确定EOQ：需求确定 90、非瞬时 10、允许缺货 20、高服务水平 60
                {70, 90, 30, 70},   // POQ：非瞬时场景最佳
                {80, 20, 90, 40},   // 允许缺货EOQ：缺货容忍场景最佳
                {60, 30, 70, 90}};  // (s,Q)：随机需求高服务水平场景最佳
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("rows", MODEL_NAMES);
        heatmap.put("columns", List.of("需求确定", "补货非瞬时", "允许缺货", "高服务水平"));
        List<List<Double>> data = new ArrayList<>();
        for (double[] row : fit) {
            List<Double> r = new ArrayList<>();
            for (double v : row) {
                r.add(v);
            }
            data.add(r);
        }
        heatmap.put("data", data);
        String recommend = switch (best) {
            case 0 -> "确定EOQ（需求稳定、瞬时补货、不允许缺货）";
            case 1 -> "非瞬时补货POQ（自产批量、p>d）";
            case 2 -> "允许缺货EOQ（缺货成本可承受）";
            default -> "随机(s,Q)（需求波动大、服务水平要求高）";
        };
        ctx.step(String.format("推荐模型：%s；若需求随机化则 (s,Q) 更稳健，若缺货成本上升则转不允许缺货",
                recommend), Map.of("recommended", recommend));

        // 输出指标（FR-007）
        ctx.output("cost_compare", "四模型成本对比", "compare", costItems, "元");
        ctx.output("inventory_curves", "库存水平波动对比", "series", curves, "单位");
        ctx.output("model_decision", "模型选择决策树", "topo", tree, null);
        ctx.output("fit_heatmap", "各模型适用条件匹配度", "heatmap", heatmap, null);
    }
}
