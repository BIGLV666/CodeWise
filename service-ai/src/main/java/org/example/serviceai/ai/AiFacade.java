package org.example.serviceai.ai;

import java.util.List;
import java.util.function.Consumer;

/**
 * 业务层依赖的 AI 顶层接口——拆分后的核心契约。
 *
 * <p>业务代码只认识它和 {@link AiRequest}/{@link AiTarget}，不认识端点池、工厂、
 * key 池、连接池与任何具体服务商。选端点、取 key（平台 key 池 / 用户密钥解密）、
 * 连接复用、SSE 解析、故障切换都在这层之后。</p>
 */
public interface AiFacade {

    /** 同步调用，返回聚合后的全文。 */
    String chat(AiRequest request);

    /** 流式调用，逐块回调；回调在调用线程上按序执行。 */
    void chatStream(AiRequest request, Consumer<String> onChunk);

    /**
     * 平台当前可选的模型名（去重、排序），供前端渲染模型选择；不含服务商信息。
     *
     * <p>放在门面上而不是让控制器直接读端点池：控制器属于业务层，不该认识池。
     * 与 {@link AiTarget.PlatformModel} 的路由读同一份索引，保证「列得出来的都真能调到」。</p>
     */
    List<String> platformModels();
}
