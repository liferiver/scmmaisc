package com.scmmaisc.engine;

import java.util.List;
import java.util.Map;

/**
 * 场景执行器接口（R-11）：每个场景一个实现类，以 Spring bean 注册，
 * 通过 {@code engineKey()} 与 scenario.engine_key 匹配装配。
 *
 * <p>实现约束（R-05 引擎确定性铁律）：禁止未播种随机、禁止依赖无序 Map 迭代；
 * 所有随机数必须取自 {@link SimContext#random()}。</p>
 */
public interface ScenarioExecutor {

    /** 执行器标识，与 scenario.engine_key 一致（如 eoq、beer-game）。 */
    String engineKey();

    /**
     * 校验参数与约束（FR-004/FR-005）。返回错误信息列表，空列表表示通过；
     * 服务端以此为准，前端校验仅作即时反馈。
     */
    List<String> validate(Map<String, Object> params);

    /**
     * 分步执行仿真：向 context 输出步骤事件（FR-009/FR-015）。
     * 执行器应在每步之间检查 {@link SimContext#isCancelled()}，取消后尽早返回。
     *
     * @param params 参数快照（JSON 反序列化后的 LinkedHashMap）
     * @param ctx    运行上下文（随机源、取消标志、步骤输出）
     */
    void run(Map<String, Object> params, SimContext ctx);

    /**
     * 预估总步数（用于进度展示，FR-015）；返回 null 表示步数不确定。
     */
    default Integer describeSteps(Map<String, Object> params) {
        return null;
    }
}
