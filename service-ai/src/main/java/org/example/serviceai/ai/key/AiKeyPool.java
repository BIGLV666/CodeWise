package org.example.serviceai.ai.key;

import java.util.List;

/**
 * 端点 API Key 池：Provider 不再直接持有 key 列表，只按端点名来池里取。
 *
 * <p>拆出来的意义是把「key 怎么选」从「端点怎么调」里剥离——将来要做按权重、
 * 按健康度、按 429 冷却轮换，只改实现，Provider 一行不动。</p>
 *
 * <p>当前实现是等概率随机（与改造前的 {@code GetAPiKey} 完全一致），
 * 刻意不做 key 级调度：那是新功能，会改变线上行为。</p>
 */
public interface AiKeyPool {

    /** 装配期注册一个端点的 key 池；重复注册同名的以最后一次为准。 */
    void register(String endpointName, List<String> apiKeys);

    /**
     * 从该端点的池里取一个可用 key。
     *
     * @throws org.example.serviceapi.exception.BizException 池未注册或没有任何非空 key
     */
    String nextKey(String endpointName);
}
