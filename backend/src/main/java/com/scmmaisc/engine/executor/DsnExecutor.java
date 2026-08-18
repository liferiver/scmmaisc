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
 * 需求供给网络(DSN)理论与综合未来趋势仿真执行器（T062，CH11-010）。
 * 模型（轻量启发式）：构建 n 节点小世界网络（平均度 = 连接密度×(n−1)，平均路径 ≈ ln n/ln k）；
 * 传统线性链（供应商→制造→分销→零售→消费，5 跳）与 DSN（多对多、D2C/C2M、平台撮合）对比：
 * 吞吐量（基础 × 平台化 × 信息透明 × 生态壁垒折减）、平均路径长度、网络韧性（disruption_events
 * 次节点中断后的连通性损失，线性链级联失效 vs DSN 重路由）、消费者满意度。
 */
@Component
public class DsnExecutor implements ScenarioExecutor {

    private static final double UNITS_PER_NODE = 8000.0;  // 单节点年吞吐基数（件）
    private static final double LINEAR_HOPS = 5.0;        // 线性链路径长度（跳）

    @Override
    public String engineKey() {
        return "dsn";
    }

    @Override
    public List<String> validate(Map<String, Object> params) {
        List<String> errors = new ArrayList<>();
        intParam(params, "node_count", 20, 200, errors);
        doubleParam(params, "connection_density", 0.1, 1.0, errors);
        doubleParam(params, "customization", 0, 1, errors);
        doubleParam(params, "platform_degree", 0, 1, errors);
        doubleParam(params, "info_transparency", 0, 1, errors);
        doubleParam(params, "ecosystem_barrier", 0, 1, errors);
        intParam(params, "disruption_events", 0, 10, errors);
        return errors;
    }

    @Override
    public Integer describeSteps(Map<String, Object> params) {
        return 5;
    }

    @Override
    public void run(Map<String, Object> params, SimContext ctx) {
        int n = ((Number) params.get("node_count")).intValue();
        double density = ((Number) params.get("connection_density")).doubleValue();
        double customization = ((Number) params.get("customization")).doubleValue();
        double platform = ((Number) params.get("platform_degree")).doubleValue();
        double transparency = ((Number) params.get("info_transparency")).doubleValue();
        double barrier = ((Number) params.get("ecosystem_barrier")).doubleValue();
        int disruptions = ((Number) params.get("disruption_events")).intValue();

        double avgDegree = density * (n - 1);
        double avgPath = avgDegree > 2 ? Math.log(n) / Math.log(avgDegree) : n / 2.0; // 小世界

        // 步骤 1：网络构建（小世界特性）
        ctx.step(String.format("DSN 网络：%d 个节点（供应商/制造商/渠道/消费者），连接密度 %.0f%% → "
                        + "平均度 %.1f，平均路径 %.2f 跳（小世界：ln n / ln k）",
                n, density * 100, avgDegree, avgPath),
                Map.of("node_count", n, "avg_degree", round2(avgDegree), "avg_path", round2(avgPath)));

        // 步骤 2：线性链 vs DSN 结构对比
        double dsnThroughput = n * UNITS_PER_NODE * (1 + platform * 0.5) * (1 + transparency * 0.3)
                * (1 - barrier * 0.1);
        double linearThroughput = n * UNITS_PER_NODE * 0.6;
        ctx.step(String.format("结构对比：线性链单向 5 跳（供应商→制造→分销→零售→消费），吞吐 %,.0f 件；"
                        + "DSN 多向直连（D2C/C2M/平台撮合），吞吐 %,.0f 件（+%.0f%%）",
                linearThroughput, dsnThroughput, (dsnThroughput / linearThroughput - 1) * 100),
                Map.of("dsn_throughput", round2(dsnThroughput), "linear_throughput", round2(linearThroughput)));

        // 步骤 3：需求定制与平台化（C2M 拉动）
        ctx.step(String.format("需求端驱动：定制化程度 %.0f%%（C2M 按需生产），平台化程度 %.0f%%"
                        + "（平台撮合降低寻源成本）；信息透明度 %.0f%%（全网库存/产能可见）",
                customization * 100, platform * 100, transparency * 100),
                Map.of("customization", round2(customization * 100), "platform_degree", round2(platform * 100)));

        // 步骤 4：韧性测试（节点中断 → 自愈/级联失效）
        double dsnResilience = disruptions == 0 ? 100
                : Math.max(0, 100 * (1 - disruptions * 3.0 / n) * (1 - barrier * 0.3));
        double linearResilience = Math.max(0, 100 - disruptions * 25.0);
        ctx.step(String.format("韧性测试：%d 次节点中断——线性链任一关键节点中断即级联断供（韧性 %.0f%%），"
                        + "DSN 多路径重路由自愈（韧性 %.0f%%）",
                disruptions, linearResilience, dsnResilience),
                Map.of("network_resilience", round2(dsnResilience), "linear_resilience", round2(linearResilience)));

        // 步骤 5：满意度与综合对比
        double satisfaction = 55 + customization * 25 + platform * 15 + transparency * 10 - barrier * 10;
        List<Map<String, Object>> compare = List.of(
                Map.of("name", "线性-吞吐量(万件)", "value", round2(linearThroughput / 10000)),
                Map.of("name", "DSN-吞吐量(万件)", "value", round2(dsnThroughput / 10000)),
                Map.of("name", "线性-路径(跳)", "value", LINEAR_HOPS),
                Map.of("name", "DSN-路径(跳)", "value", round2(avgPath)),
                Map.of("name", "线性-韧性(%)", "value", round2(linearResilience)),
                Map.of("name", "DSN-韧性(%)", "value", round2(dsnResilience)),
                Map.of("name", "线性-满意度(%)", "value", 52.0),
                Map.of("name", "DSN-满意度(%)", "value", round2(Math.min(100, satisfaction))));
        ctx.step(String.format("DSN 综合绩效：吞吐 %,.0f 件，平均路径 %.2f 跳，韧性 %.0f%%，"
                        + "满意度 %.0f%%——网络化结构以连接冗余换取韧性，但生态壁垒与信息封闭会显著削弱优势",
                dsnThroughput, avgPath, dsnResilience, satisfaction),
                Map.of("linear_vs_dsn", compare, "customer_satisfaction", round2(satisfaction)));

        // 输出指标（FR-007）
        ctx.output("throughput", "网络总吞吐量", "scalar", round2(dsnThroughput), "件");
        ctx.output("avg_path_length", "平均路径长度", "scalar", round2(avgPath), null);
        ctx.output("network_resilience", "网络韧性(节点故障影响)", "scalar", round2(dsnResilience), "%");
        ctx.output("customer_satisfaction", "消费者满意度", "scalar", round2(Math.min(100, satisfaction)), "%");
        ctx.output("linear_vs_dsn", "传统vs DSN对比", "compare", compare, null);
    }
}
