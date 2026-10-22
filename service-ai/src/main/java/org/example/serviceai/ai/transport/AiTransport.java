package org.example.serviceai.ai.transport;

import java.util.function.Consumer;

/**
 * 出站 AI 流量的传输层：连接复用、SSE 解析、超时与 HTTP 失败分类都收敛在这里。
 *
 * <p>所有调用都是流式的（当前全部端点都发 {@code stream:true}），非流式接口由
 * {@link #send} 在流式之上聚合而来，保证「同步与流式走同一份解析代码」——
 * 两条路径各写一份解析曾导致 {@code data:} 前缀判定不一致、长回答被静默截断。</p>
 */
public interface AiTransport {

    /** 逐块回调直到流结束；失败抛 {@link AiProviderHttpException} 或业务异常。 */
    void sendStream(ChatCommand command, Consumer<String> onChunk);

    /** 聚合全文返回。 */
    default String send(ChatCommand command) {
        StringBuilder answer = new StringBuilder();
        sendStream(command, answer::append);
        return answer.toString();
    }
}
