package com.scmmaisc.engine;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 仿真引擎统一入口（T010）：校验 → 分步执行 → 步骤事件输出。
 * 纯 Java 实现，不依赖 Spring；确定性由 RandomSource(seed) 保证（R-05）。
 */
public class SimulationEngine {

    private final ScenarioExecutor executor;

    public SimulationEngine(ScenarioExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** 参数与约束校验（FR-004/FR-005）；空列表 = 通过。 */
    public List<String> validate(Map<String, Object> params) {
        return executor.validate(params);
    }

    /** 预估总步数（可为 null = 不确定，FR-015 进度）。 */
    public Integer describeSteps(Map<String, Object> params) {
        return executor.describeSteps(params);
    }

    /**
     * 分步执行仿真（FR-009）：执行器向 context 输出步骤事件；
     * listener 可选，用于逐条落库 simulation_log（R-04 过程即日志）。
     *
     * @param params   参数快照（须为 LinkedHashMap 以保持确定性，R-05）
     * @param seed     随机种子（FR-008）
     * @param listener 步骤监听器，可为 null
     * @return 步骤事件流与取消状态
     */
    public SimResult run(Map<String, Object> params, long seed, SimContext.StepListener listener) {
        return run(context(params, seed), listener);
    }

    /** 创建运行上下文（供调用方持有以支持取消，R-04/FR-015）。 */
    public SimContext context(Map<String, Object> params, long seed) {
        return new SimContext(params, seed);
    }

    /** 在指定上下文上执行（上下文由调用方创建，可先注册取消标志）。 */
    public SimResult run(SimContext ctx, SimContext.StepListener listener) {
        if (listener != null) {
            ctx.setListener(listener);
        }
        executor.run(ctx.params(), ctx);
        return new SimResult(ctx.steps(), ctx.isCancelled(), ctx.outputs());
    }
}
