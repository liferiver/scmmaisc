package com.scmmaisc.engine;

import com.scmmaisc.engine.executor.BuybackExecutor;
import com.scmmaisc.engine.executor.CarbonExecutor;
import com.scmmaisc.engine.executor.FacilityLocationExecutor;
import com.scmmaisc.engine.executor.FactoringExecutor;
import com.scmmaisc.engine.executor.PoqExecutor;
import com.scmmaisc.engine.executor.RevenueSharingExecutor;
import com.scmmaisc.engine.executor.SQPolicyExecutor;
import com.scmmaisc.engine.executor.StockoutEoqExecutor;
import com.scmmaisc.engine.executor.TransportEconomyExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公式场景教材算例测试（T046，SC-010）：≥8 个公式场景计算结果与理论值一致（误差 0）。
 * 本文件同时是二期执行器参数 key 的契约定义（与 T047–T051 场景 JSON 一致）：
 *
 * <ul>
 *   <li>POQ（CH2-004，PoqExecutor）：daily_demand / production_rate / setup_cost /
 *       holding_cost —— Qp*=√(2·C2·D/(C1·(1-d/p)))，Imax=Qp(1-d/p)，D=d×365</li>
 *   <li>缺货 EOQ（CH2-005，StockoutEoqExecutor）：annual_demand / order_cost / holding_cost /
 *       backorder_cost —— Q*=√(2·C2·D/C1·(C1+C3)/C3)，S*=Q*·C1/(C1+C3)</li>
 *   <li>随机 (s,Q)（CH2-006，SQPolicyExecutor）：demand_dist（dist{normal,μ,σ}/poisson）/
 *       order_qty / lead_time / service_level / holding_cost / stockout_cost
 *       —— SS=zα·σ·√L，s=μ·L+SS</li>
 *   <li>运输规模经济（CH2-007，TransportEconomyExecutor）：od_distances（matrix）/ transport_mode /
 *       fixed_cost_road|rail|water|air / var_cost_road|rail|water|air / batch_qty /
 *       vehicle_capacity —— 单位成本=F/Q+C×d</li>
 *   <li>回购协调（CH8，BuybackExecutor）：retail_price / wholesale_price / buyback_price /
 *       salvage_value / demand_min / demand_max —— Qr=F⁻¹((p-cr)/(p-b))，协调 b 使 Qr=Q*</li>
 *   <li>收益共享协调（CH8，RevenueSharingExecutor）：retail_price / wholesale_price /
 *       revenue_share / salvage_value / demand_min / demand_max —— 协调 w=φ·c-(1-φ)·v</li>
 *   <li>重心法选址（CH7-001，FacilityLocationExecutor）：points 矩阵 [x,y,weight] ×N、
 *       max_iter / tolerance —— 迭代收敛于加权中心</li>
 *   <li>保理（CH9-001，FactoringExecutor 扩展算例）与碳足迹（CH11-004，CarbonExecutor
 *       扩展算例）沿用一期 key</li>
 * </ul>
 *
 * 新增执行器类由 T053/T058/T059 实现；本文件先写后失败（宪法 II），执行器落地后转绿。
 */
class FormulaScenarioTest {

    // ---------- POQ 生产订购批量（CH2） ----------

    @Test
    @DisplayName("POQ：d=100/p=200/C2=100/C1=2 → Qp*=2701.85、Imax=1350.93、年总成本=2701.85")
    void poqTextbookCase() {
        SimResult result = engine(new PoqExecutor()).run(poqParams(), 42L, null);

        assertEquals(2701.85, scalar(result, "q_star"), 0.01, "Qp* = √(2×100×36500/(2×0.5))");
        assertEquals(1350.93, scalar(result, "imax"), 0.01, "Imax = Qp×(1-d/p) = 2701.85×0.5");
        assertEquals(2701.85, scalar(result, "total_cost"), 0.01, "年总成本 = C1×Imax/2 + C2×D/Qp");
        assertEquals(13.51, scalar(result, "production_runs"), 0.01, "生产批次 = D/Qp ≈ 13.51");
    }

    @Test
    @DisplayName("POQ 约束：d<p 是必要条件，d≥p 时校验拒绝")
    void poqConstraint() {
        Map<String, Object> bad = poqParams();
        bad.put("production_rate", 50);   // p < d
        List<String> errors = engine(new PoqExecutor()).validate(bad);
        assertTrue(errors.stream().anyMatch(e -> e.contains("production_rate")),
                "p<d 应报错: " + errors);
    }

    // ---------- 缺货 EOQ（CH2） ----------

    @Test
    @DisplayName("缺货 EOQ：D=10000/C2=100/C1=2/C3=5 → Q*=1183.22、S*=338.06、总成本=1690.31")
    void stockoutEoqTextbookCase() {
        SimResult result = engine(new StockoutEoqExecutor()).run(stockoutParams(), 42L, null);

        assertEquals(1183.22, scalar(result, "q_star"), 0.01, "Q* = √(2×100×10000/2×7/5)");
        assertEquals(338.06, scalar(result, "backorder_qty"), 0.01, "S* = Q×C1/(C1+C3)");
        assertEquals(845.16, scalar(result, "max_inventory"), 0.01, "最大库存 = Q-S = 845.16");
        assertEquals(1690.31, scalar(result, "total_cost"), 0.01, "TC = C1(Q-S)²/2Q + C2D/Q + C3S²/2Q");
    }

    // ---------- 随机 (s,Q) 库存策略（CH2） ----------

    @Test
    @DisplayName("(s,Q)：μ=100/σ=10/L=7/z=1.65 → SS=43.66、s=743.66")
    void sqPolicyTextbookCase() {
        SimResult result = engine(new SQPolicyExecutor()).run(sqParams(), 42L, null);

        assertEquals(43.66, scalar(result, "safety_stock"), 0.01, "SS = zα×σ×√L = 1.65×10×2.6458");
        assertEquals(743.66, scalar(result, "reorder_point"), 0.01, "s = μ×L + SS = 700 + 43.66");
    }

    // ---------- 运输规模经济（CH2） ----------

    @Test
    @DisplayName("运输规模经济：F=500/C=0.1/d=100km/Q=10 → 单位成本=60；批量 20 → 35")
    void transportEconomyTextbookCase() {
        Map<String, Object> p = transportParams(10.0);
        SimResult result = engine(new TransportEconomyExecutor()).run(p, 42L, null);

        assertEquals(60.0, scalar(result, "unit_cost"), 1e-9, "单位成本 = F/Q + C×d = 50 + 10");
        Map<String, Object> curve = series(result, "cost_curve");
        List<Number> x = xOf(curve);
        assertEquals(30, x.size(), "成本曲线 30 个批量采样点（1~车辆容量）");
        assertEquals(60.0, unitCostAt(curve, 10), 1e-9, "曲线在 Q=10 处应为 60");
        assertEquals(35.0, unitCostAt(curve, 20), 1e-9, "曲线在 Q=20 处应为 35");
    }

    // ---------- 回购契约协调（CH8） ----------

    @Test
    @DisplayName("回购协调：p=100/c=50/b=20 → Qr=Q*=350（供应链协调）；b=10/30 偏离")
    void buybackCoordination() {
        Map<String, Object> p = buybackParams(20.0);
        SimResult result = engine(new BuybackExecutor()).run(p, 42L, null);

        assertEquals(350.0, scalar(result, "optimal_order_qty"), 1e-9, "集中决策 Q* = 100+(50/80)×400");
        assertEquals(350.0, scalar(result, "retailer_order_qty"), 1e-9, "b=20 协调 → 零售商订购量 = Q*");

        Map<String, Object> low = buybackParams(10.0);
        assertTrue(scalar(engine(new BuybackExecutor()).run(low, 42L, null), "retailer_order_qty") < 350,
                "b=10 回购价过低 → 订购量低于 Q*");
        Map<String, Object> high = buybackParams(30.0);
        assertTrue(scalar(engine(new BuybackExecutor()).run(high, 42L, null), "retailer_order_qty") > 350,
                "b=30 回购价过高 → 订购量高于 Q*");
    }

    // ---------- 收益共享契约协调（CH8） ----------

    @Test
    @DisplayName("收益共享协调：p=100/c=50/φ=0.8 → 协调批发价 w=42.5 时 Qr=Q*=350")
    void revenueSharingCoordination() {
        Map<String, Object> p = sharingParams(42.5);
        SimResult result = engine(new RevenueSharingExecutor()).run(p, 42L, null);

        assertEquals(350.0, scalar(result, "optimal_order_qty"), 1e-9, "集中决策 Q* = 350");
        assertEquals(350.0, scalar(result, "retailer_order_qty"), 1e-9, "w=42.5 协调 → Qr=Q*");
    }

    // ---------- 重心法选址（CH7-001） ----------

    @Test
    @DisplayName("重心法：对称四客户点 → 中心收敛于 (5,5)，总距离=42.43")
    void facilityLocationTextbookCase() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("points", List.of(List.of(0.0, 0.0, 2.0), List.of(0.0, 10.0, 1.0),
                List.of(10.0, 0.0, 1.0), List.of(10.0, 10.0, 2.0)));
        p.put("max_iter", 50);
        p.put("tolerance", 0.01);

        SimResult result = engine(new FacilityLocationExecutor()).run(p, 42L, null);

        assertEquals(5.0, scalar(result, "center_x"), 0.01, "对称权重重心 x = Σwx/Σw = 5");
        assertEquals(5.0, scalar(result, "center_y"), 0.01, "对称权重重心 y = 5");
        assertEquals(42.43, scalar(result, "total_distance"), 0.01, "总加权距离 = 6×√50");
    }

    // ---------- 保理融资扩展算例（CH9-001，一期执行器） ----------

    @Test
    @DisplayName("保理扩展算例：1000万/60天/质押率80%/AAA → 融资800万、成本≈71013.7元、银行损失=96000元")
    void factoringExtendedCase() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("receivable_amount", 1000.0);
        p.put("receivables_period", 60);
        p.put("pledge_rate", 0.8);
        p.put("financing_rate", 0.06);
        p.put("credit_rating", "AAA");
        p.put("default_probability", 0.02);
        p.put("recourse_type", "non_recourse");
        p.put("historical_performance", 0.95);

        assertTrue(engine(new FactoringExecutor()).validate(p).isEmpty(), "扩展算例参数应通过校验");
        SimResult result = engine(new FactoringExecutor()).run(p, 42L, null);

        assertEquals(800.0, scalar(result, "finance_amount"), 1e-9, "融资金额 = 1000×80% = 800 万元");
        assertEquals(71013.7, scalar(result, "finance_cost"), 0.1,
                "融资成本 = 800万×5.4%×60/365 = 71013.7 元（AAA 评级系数 0.9）");
        assertEquals(96000.0, scalar(result, "bank_expected_loss"), 1e-9,
                "银行期望损失 = 800万×2%×60%（无追索权）");
        assertEquals(58, ((Number) value(result, "cashflow_improvement_days")).intValue(),
                "现金流改善 = 60-2（历史履约 ≥95% 放款 2 天）");
    }

    // ---------- 碳足迹扩展算例（CH11-004，一期执行器） ----------

    @Test
    @DisplayName("碳足迹扩展算例：60/20/150/600 因子、装载率 80%、清洁 20% → 总排放 599.75 吨")
    void carbonExtendedCase() {
        Map<String, Object> p = carbonParams(600.0);
        assertTrue(engine(new CarbonExecutor()).validate(p).isEmpty(), "配额 600 ≥ 排放 599.75 应通过校验");

        SimResult result = engine(new CarbonExecutor()).run(p, 42L, null);

        assertEquals(599.75, scalar(result, "total_emission"), 0.01,
                "运输 343.75 + 生产 144 + 仓储 72 + 下游 40 = 599.75 吨");
        assertEquals(0.0, scalar(result, "carbon_cost"), 1e-9, "配额内无碳税");
        assertEquals(4540000.0, scalar(result, "total_cost_with_carbon"), 0.01,
                "500万 - 清洁节能 6万 - 绿色溢价 40万");

        // 配额不足时约束拦截
        List<String> errors = engine(new CarbonExecutor()).validate(carbonParams(300.0));
        assertTrue(errors.stream().anyMatch(e -> e.contains("quota_ok")),
                "超配额应报 quota_ok 约束错误: " + errors);
    }

    // ---------- 工具 ----------

    private static SimulationEngine engine(ScenarioExecutor executor) {
        return new SimulationEngine(executor);
    }

    private static Map<String, Object> poqParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("daily_demand", 100.0);
        p.put("production_rate", 200.0);
        p.put("setup_cost", 100.0);
        p.put("holding_cost", 2.0);
        return p;
    }

    private static Map<String, Object> stockoutParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("annual_demand", 10000);
        p.put("order_cost", 100.0);
        p.put("holding_cost", 2.0);
        p.put("backorder_cost", 5.0);
        return p;
    }

    private static Map<String, Object> sqParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("demand_dist", Map.of("distType", "normal", "mean", 100.0, "sigma", 10.0));
        p.put("order_qty", 1000.0);
        p.put("lead_time", 7.0);
        p.put("service_level", 0.95);
        p.put("holding_cost", 10.0);
        p.put("stockout_cost", 50.0);
        return p;
    }

    private static Map<String, Object> transportParams(double batch) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("od_distances", List.of(List.of(100.0)));
        p.put("transport_mode", "road");
        p.put("fixed_cost_road", 500.0);
        p.put("fixed_cost_rail", 2000.0);
        p.put("fixed_cost_water", 3000.0);
        p.put("fixed_cost_air", 1000.0);
        p.put("var_cost_road", 0.1);
        p.put("var_cost_rail", 0.05);
        p.put("var_cost_water", 0.03);
        p.put("var_cost_air", 1.5);
        p.put("batch_qty", batch);
        p.put("vehicle_capacity", 30);
        return p;
    }

    private static Map<String, Object> buybackParams(double b) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("retail_price", 100.0);
        p.put("wholesale_price", 50.0);
        p.put("buyback_price", b);
        p.put("salvage_value", 20.0);
        p.put("demand_min", 100.0);
        p.put("demand_max", 500.0);
        return p;
    }

    private static Map<String, Object> sharingParams(double w) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("retail_price", 100.0);
        p.put("wholesale_price", w);
        p.put("revenue_share", 0.8);
        p.put("salvage_value", 20.0);
        p.put("demand_min", 100.0);
        p.put("demand_max", 500.0);
        return p;
    }

    private static Map<String, Object> carbonParams(double quota) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("carbon_factors", Map.of("sea", 60.0, "rail", 20.0, "road", 150.0, "air", 600.0));
        p.put("electricity_factor", 600.0);
        p.put("carbon_tax_price", 100.0);
        p.put("carbon_quota", quota);
        p.put("green_premium", 0.02);
        p.put("clean_energy_ratio", 0.2);
        p.put("loading_rate", 0.8);
        p.put("supplier_distance", List.of(List.of(500.0), List.of(300.0), List.of(200.0)));
        return p;
    }

    private static Object value(SimResult result, String key) {
        return result.outputs().stream().filter(o -> o.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("缺少输出指标: " + key)).value();
    }

    private static double scalar(SimResult result, String key) {
        return ((Number) value(result, key)).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> series(SimResult result, String key) {
        return (Map<String, Object>) value(result, key);
    }

    @SuppressWarnings("unchecked")
    private static List<Number> xOf(Map<String, Object> series) {
        return (List<Number>) series.get("x");
    }

    private static double unitCostAt(Map<String, Object> curve, double q) {
        List<Number> x = xOf(curve);
        List<Map<String, Object>> s = (List<Map<String, Object>>) curve.get("series");
        List<Number> y = (List<Number>) s.get(0).get("data");
        int idx = x.indexOf(q);
        assertTrue(idx >= 0, "曲线应包含批量 " + q);
        return y.get(idx).doubleValue();
    }
}
