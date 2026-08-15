package com.scmmaisc.engine;

import java.util.Map;

/**
 * 步骤事件：一次执行过程中输出的一条日志记录（对应 simulation_log 一行）。
 *
 * @param stepNo    步骤序号（从 1 开始，全事件类型共享序号）
 * @param eventType STEP / INFO / WARN / ERROR
 * @param message   步骤说明（中文，展示于分步回放）
 * @param data      步骤快照（中间状态），不可为 null（空快照用 Map.of()）
 */
public record StepEvent(int stepNo, String eventType, String message, Map<String, Object> data) {
}
