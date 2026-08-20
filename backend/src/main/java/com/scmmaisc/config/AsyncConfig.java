package com.scmmaisc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步执行配置（T023，R-04）：仿真运行在线程池中异步执行，
 * 引擎步骤经 StepListener 逐条落库 simulation_log，前端轮询进度。
 */
@Configuration
public class AsyncConfig {

    @Bean("runExecutor")
    public ThreadPoolTaskExecutor runExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("sim-run-");
        executor.initialize();
        return executor;
    }

    /**
     * 讨论调度线程池（FR-015 有界并发，research.md §3）：core=max=4 天然实现
     * 「同时最多 4 个并行」，超出进入队列等待（前端展示排队位置），队列上限 200。
     */
    @Bean("discussionExecutor")
    public ThreadPoolTaskExecutor discussionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("discussion-");
        executor.initialize();
        return executor;
    }
}
