package com.scmmaisc.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 综合难度场景的子模型步骤聚合器（T045，R-13）。
 *
 * <p>综合场景（CH5-008 / CH7-008 / CH8-008 / CH9-006）复用对应章节子模型执行器组合运行：
 * 每个子模型在自己的 {@link SimContext}（派生种子）中执行，完成后由本类
 * 向父上下文输出 <b>1 个</b>聚合事件（分层聚合），并可选地把子模型输出指标
 * 以阶段前缀并入父上下文（供导出/对比）。单次 run 事件数 = 阶段数，远低于
 * {@link SimContext#MAX_STEPS}（宪法 IV 有界日志），同时保留子模型步骤明细供
 * 分步回放（FR-009）与导出（R-13）。
 *
 * <p>确定性（FR-008/SC-005）：子模型种子由 {@link #childSeed} 从父种子确定性派生，
 * 相同 seed + 相同阶段序列 → 完全一致的结果。
 */
public final class StepAggregator {

    private StepAggregator() {
    }

    /**
     * 子模型派生种子：baseSeed × 31 + stage，确定性、跨阶段互异。
     * 父 run seed 相同时，综合场景结果可复现。
     */
    public static long childSeed(long baseSeed, int stage) {
        return baseSeed * 31L + stage;
    }

    /**
     * 在派生种子上运行子模型执行器，返回其上下文（供聚合/输出合并）。
     *
     * @param sub        子模型执行器
     * @param subParams  子模型参数快照（须为 LinkedHashMap，R-05）
     * @param baseSeed   父上下文种子
     * @param stage      阶段序号（从 1 开始）
     */
    public static SimContext runSubModel(ScenarioExecutor sub, Map<String, Object> subParams,
                                         long baseSeed, int stage) {
        SimContext child = new SimContext(subParams, childSeed(baseSeed, stage));
        sub.run(subParams, child);
        return child;
    }

    /**
     * 把子模型步骤聚合为父上下文中的 1 个事件。
     * 事件 data 含：model / stage_no / child_step_count / first_message / last_message /
     * steps（全量子步骤明细，供导出与回放）。
     *
     * @param parent      父上下文（综合场景执行器持有的 ctx）
     * @param stageNo     阶段序号（从 1 开始，与 runSubModel 的 stage 一致）
     * @param stageLabel  阶段中文名，如「第 1 步：预测子模型」
     * @param modelKey    子模型执行器 engineKey（如 forecast）
     * @param child       子模型上下文（runSubModel 的返回值）
     */
    public static void aggregate(SimContext parent, int stageNo, String stageLabel,
                                 String modelKey, SimContext child) {
        List<StepEvent> childSteps = child.steps();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", modelKey);
        data.put("stage_no", stageNo);
        data.put("child_step_count", childSteps.size());
        if (!childSteps.isEmpty()) {
            data.put("first_message", childSteps.get(0).message());
            data.put("last_message", childSteps.get(childSteps.size() - 1).message());
            List<Map<String, Object>> detail = new ArrayList<>(childSteps.size());
            for (StepEvent e : childSteps) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("step_no", e.stepNo());
                m.put("event_type", e.eventType());
                m.put("message", e.message());
                m.put("data", e.data());
                detail.add(m);
            }
            data.put("steps", detail);
        }
        parent.step(String.format("阶段 %d：%s（子模型 %s 产生 %d 个步骤事件）",
                stageNo, stageLabel, modelKey, childSteps.size()), data);
    }

    /**
     * 把子模型输出指标并入父上下文：key 加 {@code s{stage}_} 前缀（跨阶段不冲突），
     * label 加阶段标注。综合场景的导出数据由此保留各子模型明细（R-13）。
     */
    public static void mergeOutputs(SimContext parent, SimContext child, int stageNo) {
        for (OutputValue o : child.outputs()) {
            parent.output("s" + stageNo + "_" + o.key(),
                    "阶段" + stageNo + "·" + o.label(), o.type(), o.value(), o.unit());
        }
    }
}
