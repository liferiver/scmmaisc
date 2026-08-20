package com.scmmaisc.llm;

/** LLM 调用异常：网络失败、非 2xx、响应结构异常、内容为空（边界显式抛出，不吞错）。 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
