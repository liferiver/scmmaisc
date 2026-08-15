package com.scmmaisc.engine;

import java.util.List;

/**
 * 执行结果：步骤事件流、输出指标与取消状态。
 *
 * @param steps     全部已产生的步骤事件（按 stepNo 升序）
 * @param cancelled 执行是否被取消（执行器在步骤间检查取消标志后提前返回）
 * @param outputs   输出指标（按执行器声明顺序，FR-007）
 */
public record SimResult(List<StepEvent> steps, boolean cancelled, List<OutputValue> outputs) {
}
