package com.scmmaisc.llm;

import java.util.List;

/**
 * 语言模型客户端抽象（research.md §10）：生产走 HTTP（OpenAI 兼容），
 * 测试与离线演示走 Stub（宪法 II：确定性、无网络）。
 */
public interface LlmClient {

    /**
     * 同步请求一次补全。
     *
     * @param messages    消息列表（system 在前，最后一条为当前指令）
     * @param temperature 采样温度（0-2）
     * @return 模型回复文本
     * @throws LlmException 调用失败或响应结构异常（错误在边界显式处理，不静默吞错）
     */
    String complete(List<LlmMessage> messages, double temperature);
}
