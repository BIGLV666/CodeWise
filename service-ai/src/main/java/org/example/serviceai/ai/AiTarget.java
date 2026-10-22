package org.example.serviceai.ai;

/**
 * 请求要走哪条路。业务层只描述用户的<b>显式选择</b>，选端点、拿 key、发请求都由实现层决定。
 *
 * <p>用 sealed 把可选目标收敛成穷举的几种：新增一种目标时编译器会指出所有需要处理的分支。
 * 改造前这类决策是业务代码里的 {@code if (useAutoProvider)} 分支，散落且不可穷举。</p>
 *
 * <p>模型名一律由 {@link AiRequest#modelName()} 携带，target 只表达"用户选了哪一档"——
 * "到底该用哪个模型"是这条链路最要紧的信息，只存一处才不会漂移。</p>
 */
public sealed interface AiTarget {

    /**
     * AUTO：用户什么都不选，由平台决定。
     *
     * <p>按优先级与健康状态轮转端点，用<b>被借到那个端点的默认模型</b>——所以同一个服务商
     * 下 AUTO 的模型是稳定的、不会每次请求漂移。</p>
     */
    record PlatformAuto() implements AiTarget {
    }

    /**
     * 指定平台端点（按身份借用，不做故障切换）：用于会话摘要这类固定走某个端点的场景，
     * 模型用该端点的默认模型。
     */
    record PlatformProvider(String identity) implements AiTarget {
    }

    /**
     * 指定平台模型：用户明确选了「平台」栏里的某个模型。
     *
     * <p>不带字段——具体是哪个模型由 {@link AiRequest#modelName()} 给出，模型名只存一处，
     * 避免两处各存一份后漂移。从声明了该模型的端点里随机取一个，<b>不轮转也不兜底</b>；
     * 失败不会退化成 AUTO、也不会改用别的端点的默认模型——用户选了模型就按用户选的来。</p>
     */
    record PlatformModel() implements AiTarget {
    }

    /**
     * 用户自带配置（{@code user_ai_config}）：地址与密钥都用这一行，不进平台轮转、
     * 也不与平台端点互相兜底。
     *
     * <p>模型同样由 {@link AiRequest#modelName()} 给出，须属于该配置的 {@code modelNames}。</p>
     */
    record UserConfig(Long userId, Long configId) implements AiTarget {
    }
}
