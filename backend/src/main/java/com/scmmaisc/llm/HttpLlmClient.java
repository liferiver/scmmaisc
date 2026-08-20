package com.scmmaisc.llm;

import com.scmmaisc.config.LlmProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP 语言模型客户端（research.md §1/§2）：调用 OpenAI 兼容 {@code POST /chat/completions}，
 * 零新增依赖（Spring 6.1 RestClient），connect/read 超时与错误映射在此边界内显式完成。
 * 密钥经环境变量注入（{@code LLM_API_KEY}），配置缺失时构造即显式报错（宪法安全）。
 */
public class HttpLlmClient implements LlmClient {

    private final RestClient restClient;
    private final String model;
    private final LlmProperties props;

    public HttpLlmClient(LlmProperties props, RestClient.Builder builder) {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "LLM 未配置：llm.api-key 为空。生产模式请设置环境变量 LLM_API_KEY；"
                            + "本地演示/测试请启用 llm.stub=true 使用内置 Stub 模式");
        }
        this.props = props;
        this.model = props.getModel();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(props.getTimeoutSeconds()));
        this.restClient = builder
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getApiKey())
                .requestFactory(factory)
                .build();
    }

    @Override
    public String complete(List<LlmMessage> messages, double temperature) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                "temperature", temperature);
        ChatResponse response;
        try {
            response = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientException e) {
            throw new LlmException("LLM 调用失败: " + e.getMessage(), e);
        }
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().get(0).message() == null) {
            throw new LlmException("LLM 响应结构异常：缺少 choices[0].message");
        }
        String content = response.choices().get(0).message().content();
        if (content == null || content.isBlank()) {
            throw new LlmException("LLM 响应内容为空");
        }
        return content;
    }

    /** OpenAI 兼容响应体（仅声明所需字段）。 */
    public record ChatResponse(List<Choice> choices) {
    }

    public record Choice(Message message) {
    }

    public record Message(String role, String content) {
    }
}
