package com.scmmaisc.config;

import com.scmmaisc.llm.HttpLlmClient;
import com.scmmaisc.llm.LlmClient;
import com.scmmaisc.llm.StubLlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * LLM 客户端装配（research.md §10）：{@code llm.stub=true}（或环境变量 {@code LLM_STUB=true}）
 * 装配 StubLlmClient（测试/离线演示），否则装配 HttpLlmClient（生产，密钥缺失时显式报错）。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    @Bean
    public LlmClient llmClient(LlmProperties props, RestClient.Builder builder) {
        return props.isStub() ? new StubLlmClient() : new HttpLlmClient(props, builder);
    }
}
