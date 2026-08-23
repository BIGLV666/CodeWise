package org.example.serviceai.handle.testcasehandle;

import org.example.serviceai.MQ.AiMessageHandler;

/**
 * 测试用例生成类消息处理器标记接口：ACK/重试/死信纪律统一由 {@code Mq#mq} 分发器负责。
 */
public interface TestCaseHandle extends AiMessageHandler {
}
