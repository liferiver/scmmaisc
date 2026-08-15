package com.scmmaisc.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 执行器注册表（R-11）：由 Spring 注入全部 ScenarioExecutor bean，
 * 按 engineKey() 建立映射；新增场景 = 新增 bean + JSON 定义，引擎框架零改动。
 * 纯 Java 实现；Spring 装配见 config/EngineConfig。
 */
public class ExecutorRegistry {

    private final Map<String, ScenarioExecutor> byKey;

    public ExecutorRegistry(List<ScenarioExecutor> executors) {
        Map<String, ScenarioExecutor> map = new LinkedHashMap<>();
        for (ScenarioExecutor executor : executors) {
            map.put(executor.engineKey(), executor);
        }
        this.byKey = Collections.unmodifiableMap(map);
    }

    /** 按 engine_key 获取执行器；未注册时抛 IllegalArgumentException。 */
    public ScenarioExecutor require(String engineKey) {
        ScenarioExecutor executor = byKey.get(engineKey);
        if (executor == null) {
            throw new IllegalArgumentException("未注册的执行器: " + engineKey);
        }
        return executor;
    }

    public Optional<ScenarioExecutor> find(String engineKey) {
        return Optional.ofNullable(byKey.get(engineKey));
    }

    public boolean contains(String engineKey) {
        return byKey.containsKey(engineKey);
    }
}
