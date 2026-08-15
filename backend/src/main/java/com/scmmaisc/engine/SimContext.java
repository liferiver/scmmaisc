package com.scmmaisc.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运行上下文（T010）：参数、随机种子、取消标志、步骤事件流。
 * 纯 Java 实现，不依赖 Spring，便于确定性单元测试。
 */
public class SimContext {

    /** 单次运行步骤上限（宪法 IV：日志与内存增长有界）。 */
    public static final int MAX_STEPS = 5000;

    private final Map<String, Object> params;
    private final RandomSource random;
    private final List<StepEvent> steps = new ArrayList<>();
    private final List<OutputValue> outputs = new ArrayList<>();
    private volatile boolean cancelled;
    private StepListener listener;

    public SimContext(Map<String, Object> params, long seed) {
        this.params = Objects.requireNonNull(params, "params");
        this.random = new RandomSource(seed);
    }

    /** 运行参数（不可修改视图）。 */
    public Map<String, Object> params() {
        return params;
    }

    /** 可复现随机源（R-05：java.util.Random(seed)，执行器唯一随机入口）。 */
    public RandomSource random() {
        return random;
    }

    /** 注册步骤监听器（RunService 用于逐条落库 simulation_log）。 */
    public void setListener(StepListener listener) {
        this.listener = listener;
    }

    /** 请求取消：执行器在下一步前检查并提前返回（FR-015）。 */
    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** 已产生的步骤事件（不可修改视图）。 */
    public List<StepEvent> steps() {
        return List.copyOf(steps);
    }

    /**
     * 输出一个结果指标（FR-007）：执行器在 run() 内按确定性顺序调用，
     * 最终汇总为 simulation_run.result（C6 输出指标）。
     */
    public void output(String key, String label, String type, Object value, String unit) {
        outputs.add(new OutputValue(key, label, type, value, unit));
    }

    /** 已产生的输出指标（不可修改视图，插入序 = 声明序）。 */
    public List<OutputValue> outputs() {
        return List.copyOf(outputs);
    }

    /** 输出一个业务步骤（进度计数、分步回放主体）。 */
    public void step(String message, Map<String, Object> data) {
        emit("STEP", message, data);
    }

    /** 输出一条提示信息（如参数说明、公式引用）。 */
    public void info(String message) {
        emit("INFO", message, Map.of());
    }

    /** 输出一条警告（如极端参数下结果趋于异常）。 */
    public void warn(String message, Map<String, Object> data) {
        emit("WARN", message, data);
    }

    /** 输出一条错误（引擎异常由调用方捕获，此处仅作日志记录）。 */
    public void error(String message) {
        emit("ERROR", message, Map.of());
    }

    private void emit(String eventType, String message, Map<String, Object> data) {
        if (steps.size() >= MAX_STEPS) {
            throw new IllegalStateException("步骤数超过上限 " + MAX_STEPS);
        }
        StepEvent event = new StepEvent(steps.size() + 1, eventType, message,
                data == null ? Map.of() : data);
        steps.add(event);
        if (listener != null) {
            listener.onStep(event);
        }
    }

    /** 步骤监听器：每次输出事件时回调（供日志落库）。 */
    @FunctionalInterface
    public interface StepListener {
        void onStep(StepEvent event);
    }
}
