package org.example.serviceai.ai.pool;

import org.example.serviceai.ai.provider.AiProvider;

import java.util.Map;

/**
 * AI 端点池：只负责「借一个端点」和「回执成败」，不做重试——重试是策略，归
 * {@link org.example.serviceai.ai.AiFacade}。
 *
 * <p>池里有<b>两套键空间，刻意分开</b>：端点是「谁」（身份，用于健康记账与按名借用），
 * 模型是「能力」（一个端点可以声明提供多个模型，一个模型也可以被多个端点提供）。
 * 把两者混成一个索引就会重演改造前的错误——拿模型名当端点身份用。</p>
 */
public interface AiPool {

    /**
     * 按优先级轮转到第一个健康端点（AUTO）。
     *
     * @return 健康端点；<b>全部不健康时返回 null</b>（降级，由调用方决定是否失败）
     * @throws org.example.serviceapi.exception.BizException 池里没有任何可轮转端点
     */
    AiProvider borrowAuto();

    /**
     * 按<b>身份</b>借用指定端点，<b>不看健康状态</b>——指定端点的路径（如 ollama 摘要）
     * 改造前就是直连、不参与健康门控，必须保持一致。
     *
     * @return 指定端点；未注册时返回 null
     */
    AiProvider borrowByIdentity(String identity);

    /**
     * 按<b>模型</b>借用端点：从声明了该模型的端点里随机取一个（负载均衡）。
     *
     * <p>不看健康状态、也不预留轮转指针——指定模型是"用户已经决定用哪个模型"，
     * 失败不兜底，因此这里不做任何健康门控（否则会把模型级故障连坐到 AUTO 上）。</p>
     *
     * @return 声明了该模型的端点；<b>没有任何端点声明时返回 null</b>（由调用方报错）
     */
    AiProvider borrowForModel(String modelName);

    /**
     * 平台对外提供的模型名列表（去重、排序），供前端渲染模型选择；不含服务商信息。
     *
     * <p>与 {@link #borrowForModel} 读同一份索引，保证「列得出来的都真能路由到」。</p>
     */
    java.util.List<String> platformModels();

    /** 调用成功回执：清零失败计数并标记为健康。 */
    void reportSuccess(AiProvider provider);

    /** 调用失败回执：累计到阈值后标记不可用并切到下一个。 */
    void reportFailure(AiProvider provider);

    /** 手动切到下一个自动轮转端点。 */
    void switchToNext();

    /** 手动把轮转指针切到指定端点（按身份），不存在则返回 false。 */
    boolean switchTo(String identity);

    /** 自动轮转集合的健康快照，用于运维观察。 */
    Map<String, Boolean> status();
}
