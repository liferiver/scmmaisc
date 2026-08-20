package com.scmmaisc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 接入配置（research.md §1/§10）：密钥仅走环境变量（{@code LLM_API_KEY}，不提交），
 * stub 开关支持离线演示与确定性测试（宪法 II）。
 */
@Data
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** OpenAI 兼容端点根地址（不含 /chat/completions）。 */
    private String baseUrl = "http://localhost:8000/v1";

    /** 模型名。 */
    private String model = "qwen2.5-72b-instruct";

    /** 密钥（环境变量 LLM_API_KEY 注入；为空且非 stub 时构造 HttpLlmClient 显式报错）。 */
    private String apiKey = "";

    /** 单次补全 read 超时（秒）。 */
    private int timeoutSeconds = 60;

    /** true=使用内置 Stub 语言模型（确定性、无网络）；false=HTTP 生产模式。 */
    private boolean stub = false;
}
