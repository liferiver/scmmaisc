package com.scmmaisc.config;

import com.scmmaisc.engine.ExecutorRegistry;
import com.scmmaisc.engine.ScenarioExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 引擎装配（R-11）：收集全部 ScenarioExecutor bean，按 engineKey() 建立注册表。
 * 新增场景执行器 = 新增 @Component 实现类，此处零改动。
 */
@Configuration
public class EngineConfig {

    @Bean
    public ExecutorRegistry executorRegistry(List<ScenarioExecutor> executors) {
        return new ExecutorRegistry(executors);
    }
}
