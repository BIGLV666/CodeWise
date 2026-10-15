package org.example.serviceai.ai.facade;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.ai.AiFacade;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.AiTarget;
import org.example.serviceai.ai.config.UserAiCall;
import org.example.serviceai.ai.config.UserAiConfigService;
import org.example.serviceai.ai.pool.AiPool;
import org.example.serviceai.ai.pool.AiServiceFactory;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.ai.transport.AiProviderHttpException;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * {@link AiFacade} 的默认实现：按 {@link AiTarget} 分派，并持有重试/故障切换策略。
 *
 * <p>职责边界很窄——只有「目标分派」与「失败后怎么办」是策略；借端点、取 key、
 * 连接复用、SSE 解析分别由 {@link AiPool}、key 池、传输层负责。</p>
 *
 * <p>三条路径的失败策略刻意不同：AUTO 会换端点重试一次，指定端点（摘要）与指定模型都不兜底，
 * 用户自带配置也不与平台互相兜底——「平台不能替用户决定」这条原则就落在这里。</p>
 */
@Service
@Slf4j
public class DefaultAiFacade implements AiFacade {

    private final AiPool pool;
    private final AiServiceFactory factory;
    private final UserAiConfigService userAiConfigService;

    public DefaultAiFacade(
            AiPool pool,
            AiServiceFactory factory,
            UserAiConfigService userAiConfigService
    ) {
        this.pool = pool;
        this.factory = factory;
        this.userAiConfigService = userAiConfigService;
    }

    @Override
    public String chat(AiRequest request) {
        return switch (request.target()) {
            case AiTarget.PlatformAuto() -> chatWithFailover(request);
            case AiTarget.PlatformProvider named -> chatNamed(request, named.identity());
            case AiTarget.PlatformModel() -> chatByModel(request);
            case AiTarget.UserConfig userConfig -> userConfigProvider(request, userConfig)
                    .chat(request);
        };
    }

    @Override
    public void chatStream(AiRequest request, Consumer<String> onChunk) {
        switch (request.target()) {
            case AiTarget.PlatformAuto() -> streamWithFailover(request, onChunk);
            case AiTarget.PlatformProvider named -> requireIdentity(named.identity())
                    .chatStream(request, onChunk);
            case AiTarget.PlatformModel() -> streamByModel(request, onChunk);
            case AiTarget.UserConfig userConfig -> userConfigProvider(request, userConfig)
                    .chatStream(request, onChunk);
        }
    }

    @Override
    public java.util.List<String> platformModels() {
        return pool.platformModels();
    }

    /**
     * AUTO：借一个健康端点，失败则记账并切到下一个端点重试一次。
     *
     * <p>模型一律用<b>被借到那个端点的默认模型</b>（传 null 作为覆盖），不读请求里的模型名——
     * AUTO 的语义就是"服务商由平台定"，把某个模型名强行套到不同端点上是错误的
     * （会拿 A 服务商的模型名去请求 B 服务商）。</p>
     *
     * <p>只重试一次：连换两个端点都失败说明问题不在单个端点（网络/配置/额度），
     * 继续重试只会放大故障——更深层的重试交给 MQ 消息重投。</p>
     */
    private String chatWithFailover(AiRequest request) {
        AiProvider first = pool.borrowAuto();
        if (first == null) {
            throw new BizException(AiErrorCode.AI_SERVICE_NOT_READY);
        }

        long start = System.currentTimeMillis();
        try {
            log.info("Calling AI provider {}", first.name());
            String result = first.chat(request);
            pool.reportSuccess(first);
            log.info("AI provider {} succeeded in {}ms", first.name(), elapsed(start));
            return result;
        } catch (Exception firstFailure) {
            logFailure("AI provider " + first.name(), start, firstFailure);
            pool.reportFailure(first);

            AiProvider next = borrowNext(first);
            if (next == null) {
                throw combinedFailure(first, firstFailure, null, null);
            }

            long retryStart = System.currentTimeMillis();
            try {
                log.info("Retrying AI with provider {}", next.name());
                String result = next.chat(request);
                pool.reportSuccess(next);
                log.info("AI provider {} retry succeeded in {}ms", next.name(), elapsed(retryStart));
                return result;
            } catch (Exception retryFailure) {
                logFailure("AI provider " + next.name() + " retry", retryStart, retryFailure);
                pool.reportFailure(next);
                throw combinedFailure(first, firstFailure, next, retryFailure);
            }
        }
    }

    private void streamWithFailover(AiRequest request, Consumer<String> onChunk) {
        AiProvider first = pool.borrowAuto();
        if (first == null) {
            throw new BizException(AiErrorCode.AI_SERVICE_NOT_READY);
        }

        long start = System.currentTimeMillis();
        AtomicBoolean emitted = new AtomicBoolean(false);
        try {
            log.info("Streaming AI with provider {}", first.name());
            first.chatStream(request, chunk -> {
                emitted.set(true);
                onChunk.accept(chunk);
            });
            pool.reportSuccess(first);
            log.info("Streaming AI provider {} succeeded in {}ms", first.name(), elapsed(start));
        } catch (Exception firstFailure) {
            logFailure("Streaming AI provider " + first.name(), start, firstFailure);
            pool.reportFailure(first);

            // 已经吐给用户的内容收不回来：不能再接另一个模型的回答，否则前端看到两段拼接
            if (emitted.get()) {
                throw combinedFailure(first, firstFailure, null, null);
            }

            AiProvider next = borrowNext(first);
            if (next == null) {
                throw combinedFailure(first, firstFailure, null, null);
            }

            long retryStart = System.currentTimeMillis();
            try {
                log.info("Retrying streaming AI with provider {}", next.name());
                next.chatStream(request, onChunk);
                pool.reportSuccess(next);
                log.info("Streaming AI provider {} retry succeeded in {}ms", next.name(), elapsed(retryStart));
            } catch (Exception retryFailure) {
                logFailure("Streaming AI provider " + next.name() + " retry", retryStart, retryFailure);
                pool.reportFailure(next);
                throw combinedFailure(first, firstFailure, next, retryFailure);
            }
        }
    }

    /**
     * 指定平台端点：按身份借用，<b>不做故障切换</b>。
     *
     * <p>刻意保持改造前的语义——会话摘要路径固定用某个端点（如 ollama），换别的模型
     * 可能产出不符合解析要求的摘要。失败就失败，由上层决定是否重试。</p>
     */
    private String chatNamed(AiRequest request, String identity) {
        AiProvider provider = requireIdentity(identity);
        long start = System.currentTimeMillis();
        try {
            String result = provider.chat(request);
            pool.reportSuccess(provider);
            return result;
        } catch (Exception failure) {
            logFailure("AI provider " + identity, start, failure);
            pool.reportFailure(provider);
            throw failure;
        }
    }

    /**
     * 指定模型：按模型路由到声明了它的端点（同模型多端点时随机取一个），模型名原样下发。
     *
     * <p><b>不兜底</b>：这里既不会退化成 AUTO、也不会换成该端点的默认模型——用户选了模型
     * 就按用户选的来，服务商挂了让用户自己换模型。</p>
     *
     * <p><b>不记端点健康</b>：把"某个模型在这个端点不可用"记成端点失败，三次就会把整个
     * 服务商从 AUTO 轮转里摘掉，正好破坏 AUTO 的稳定性。代价是这条路径的失败不会帮 AUTO
     * 发现故障——这是刻意的取舍。</p>
     */
    private String chatByModel(AiRequest request) {
        String modelName = request.modelName();
        AiProvider provider = requireModelProvider(modelName);
        long start = System.currentTimeMillis();
        try {
            String result = provider.chat(request);
            log.info("AI provider {} served model {} in {}ms", provider.name(), modelName, elapsed(start));
            return result;
        } catch (Exception failure) {
            logFailure("AI provider " + provider.name() + " model " + modelName, start, failure);
            throw modelFailure(failure);
        }
    }

    private void streamByModel(AiRequest request, Consumer<String> onChunk) {
        String modelName = request.modelName();
        AiProvider provider = requireModelProvider(modelName);
        long start = System.currentTimeMillis();
        try {
            provider.chatStream(request, onChunk);
            log.info("AI provider {} streamed model {} in {}ms", provider.name(), modelName, elapsed(start));
        } catch (Exception failure) {
            logFailure("AI provider " + provider.name() + " model " + modelName, start, failure);
            throw modelFailure(failure);
        }
    }

    private AiProvider requireModelProvider(String modelName) {
        AiProvider provider = pool.borrowForModel(modelName);
        if (provider == null) {
            // 平台根本没有这个模型：直接让用户换，绝不退化成 AUTO 或别的端点的默认模型
            throw new BizException(AiErrorCode.MODEL_NOT_SERVED);
        }
        return provider;
    }

    /**
     * 指定模型失败后的异常。
     *
     * <p>已经分类过的业务异常（密钥无效、额度不足）原样上抛——它们的错误码与文案都是对的，
     * 重包会把"密钥错了"说成"换个模型"，还会击穿上层对 {@code AiProviderHttpException}
     * 的专门分支。</p>
     *
     * <p>但上游的 4xx（除密钥/额度）在"指定模型"这条路上几乎都是"这个模型用不了"
     * ——例如模型 id 不存在或套餐不含（OpenCode Go 会回 {@code Model is unavailable}）。
     * 这时换成 {@link AiErrorCode#MODEL_UNRESPONSIVE}（"请更换模型或稍后重试"）比
     * {@code PROVIDER_CALL_FAILED}（"AI 服务繁忙，请稍后重试"）准确得多：后者会让人
     * 反复重试一个永远不会成功的请求。</p>
     */
    private RuntimeException modelFailure(Exception failure) {
        if (failure instanceof AiProviderHttpException httpFailure) {
            int status = httpFailure.getStatusCode();
            boolean alreadyClassified = status == 401 || status == 403 || status == 429;
            if (status >= 400 && status < 500 && !alreadyClassified) {
                return new BizException(AiErrorCode.MODEL_UNRESPONSIVE, failure);
            }
            return httpFailure;
        }
        if (failure instanceof BizException classified) {
            return classified;
        }
        return new BizException(AiErrorCode.MODEL_UNRESPONSIVE, failure);
    }

    private AiProvider requireIdentity(String identity) {
        AiProvider provider = pool.borrowByIdentity(identity);
        if (provider == null) {
            throw new BizException(AiErrorCode.AI_SERVICE_NOT_READY);
        }
        return provider;
    }

    /** 用户自带配置：先校验取参数（含归属与内网地址拦截），再交给工厂造一次性端点。 */
    private AiProvider userConfigProvider(AiRequest request, AiTarget.UserConfig target) {
        UserAiCall call = userAiConfigService.requireCallableConfig(
                target.userId(), target.configId(), request.modelName());
        return factory.createUserConfigProvider(
                call.configId(), call.endpointUrl(), call.apiKey(), call.model(), call.timeout());
    }

    private AiProvider borrowNext(AiProvider failed) {
        pool.switchToNext();
        AiProvider next = pool.borrowAuto();
        return next == failed ? null : next;
    }

    private void logFailure(String provider, long start, Throwable failure) {
        log.error("{} failed after {}ms, timeout={}, rootCause={}",
                provider, elapsed(start), isTimeout(failure), rootCause(failure), failure);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current.getClass().getName().toLowerCase().contains("timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootCause(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null
                ? "unknown"
                : current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    /**
     * 端点都失败后的统一异常：消息取枚举固定文案，原始根因（服务商名、超时类型等）
     * 只挂在 cause/suppressed 上供日志排查，不透传前端。
     */
    private BizException combinedFailure(
            AiProvider first,
            Exception firstFailure,
            AiProvider second,
            Exception secondFailure
    ) {
        Exception cause = secondFailure == null ? firstFailure : secondFailure;
        BizException result = new BizException(AiErrorCode.PROVIDER_CALL_FAILED, cause);
        if (firstFailure != null && secondFailure != null) {
            result.addSuppressed(firstFailure);
        }
        return result;
    }
}
