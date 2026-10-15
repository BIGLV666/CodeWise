package org.example.serviceai.ai.facade;

import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.config.UserAiCall;
import org.example.serviceai.ai.config.UserAiConfigService;
import org.example.serviceai.ai.pool.AiPool;
import org.example.serviceai.ai.pool.AiServiceFactory;
import org.example.serviceai.ai.provider.AiProvider;
import org.example.serviceai.ai.transport.AiProviderHttpException;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 故障切换策略：自动轮转失败后换一个端点重试一次；已经吐过内容就不再换端点
 * （否则前端看到两段拼接）；指定端点与用户自带端点不做故障切换。
 */
@ExtendWith(MockitoExtension.class)
class DefaultAiFacadeTest {

    @Mock
    private AiPool pool;
    @Mock
    private AiServiceFactory factory;
    @Mock
    private UserAiConfigService userAiConfigService;
    @InjectMocks
    private DefaultAiFacade facade;

    private final AiProvider first = provider("p1");
    private final AiProvider second = provider("p2");

    @Test
    void autoUsesHealthyProviderAndReportsSuccess() {
        when(pool.borrowAuto()).thenReturn(first);
        when(first.chat(any(AiRequest.class))).thenReturn("answer");

        assertThat(facade.chat(AiRequest.auto("hi"))).isEqualTo("answer");
        verify(pool).reportSuccess(first);
        verify(pool, never()).reportFailure(any());
    }

    @Test
    void autoFailsOverToNextProviderExactlyOnce() {
        when(pool.borrowAuto()).thenReturn(first, second);
        when(first.chat(any(AiRequest.class))).thenThrow(new IllegalStateException("first down"));
        when(second.chat(any(AiRequest.class))).thenReturn("recovered");

        assertThat(facade.chat(AiRequest.auto("hi"))).isEqualTo("recovered");

        verify(pool).reportFailure(first);
        verify(pool).switchToNext();
        verify(pool).reportSuccess(second);
    }

    @Test
    void autoGivesUpAfterTwoProvidersAndKeepsRootCauseOutOfUserMessage() {
        when(pool.borrowAuto()).thenReturn(first, second);
        RuntimeException rootCause = new RuntimeException("upstream boom");
        when(first.chat(any(AiRequest.class))).thenThrow(new IllegalStateException("first down"));
        when(second.chat(any(AiRequest.class))).thenThrow(rootCause);

        BizException failure = (BizException) catchThrowable(() -> facade.chat(AiRequest.auto("hi")));

        assertThat(failure.getCode()).isEqualTo(AiErrorCode.PROVIDER_CALL_FAILED.getCode());
        // 根因只挂在 cause 上供排查，用户只看到枚举文案
        assertThat(failure.getCause()).isSameAs(rootCause);
        assertThat(failure.getMessage()).doesNotContain("boom");
        assertThat(failure.getSuppressed()).hasSize(1);
    }

    @Test
    void autoWithoutAnyHealthyProviderFailsAsNotReady() {
        when(pool.borrowAuto()).thenReturn(null);

        assertThatThrownBy(() -> facade.chat(AiRequest.auto("hi")))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.AI_SERVICE_NOT_READY.getCode());
    }

    @Test
    void streamRetriesWhenNothingWasEmittedYet() {
        when(pool.borrowAuto()).thenReturn(first, second);
        doAnswer(invocation -> {
            throw new IllegalStateException("first down");
        }).when(first).chatStream(any(AiRequest.class), any());
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("recovered");
            return null;
        }).when(second).chatStream(any(AiRequest.class), any());

        List<String> received = new ArrayList<>();
        facade.chatStream(AiRequest.auto("hi"), received::add);

        assertThat(received).containsExactly("recovered");
        verify(pool).switchToNext();
    }

    @Test
    void streamDoesNotSwitchProviderAfterContentWasAlreadyEmitted() {
        when(pool.borrowAuto()).thenReturn(first);
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("partial");
            throw new IllegalStateException("died mid-stream");
        }).when(first).chatStream(any(AiRequest.class), any());

        List<String> received = new ArrayList<>();
        assertThatThrownBy(() -> facade.chatStream(AiRequest.auto("hi"), received::add))
                .isInstanceOf(BizException.class);

        // 已经吐给用户的内容收不回来：换端点会拼出两段回答，所以只失败不重试
        assertThat(received).containsExactly("partial");
        verify(pool, never()).switchToNext();
        verify(second, never()).chatStream(any(AiRequest.class), any());
    }

    @Test
    void namedProviderIsNotFailedOver() {
        when(pool.borrowByIdentity("ollama")).thenReturn(first);
        RuntimeException failure = new RuntimeException("ollama down");
        when(first.chat(any(AiRequest.class))).thenThrow(failure);

        assertThat(catchThrowable(() -> facade.chat(AiRequest.provider("ollama", "hi"))))
                .isSameAs(failure);

        verify(pool, never()).switchToNext();
        verify(pool).reportFailure(first);
    }

    @Test
    void unknownNamedProviderFailsAsNotReady() {
        when(pool.borrowByIdentity("absent")).thenReturn(null);

        assertThatThrownBy(() -> facade.chat(AiRequest.provider("absent", "hi")))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.AI_SERVICE_NOT_READY.getCode());
    }

    @Test
    void userConfigPathValidatesThenCallsTheDedicatedEndpoint() {
        UserAiCall call = new UserAiCall(
                7L, "https://93.184.216.34/v1/chat/completions", "user-key", "user-model",
                Duration.ofSeconds(30));
        when(userAiConfigService.requireCallableConfig(10L, 7L, "user-model")).thenReturn(call);
        when(factory.createUserConfigProvider(7L, call.endpointUrl(), "user-key", "user-model",
                Duration.ofSeconds(30))).thenReturn(second);
        when(second.chat(any(AiRequest.class))).thenReturn("custom-answer");

        assertThat(facade.chat(AiRequest.userConfig("hi", 10L, 7L, "user-model")))
                .isEqualTo("custom-answer");

        ArgumentCaptor<AiRequest> captured = ArgumentCaptor.forClass(AiRequest.class);
        verify(second).chat(captured.capture());
        assertThat(captured.getValue().modelName()).isEqualTo("user-model");
        // 用户自带端点不进池、不参与健康记账
        verify(pool, never()).reportSuccess(any());
        verify(pool, never()).reportFailure(any());
    }

    @Test
    void userConfigValidationFailureSkipsProviderCreation() {
        when(userAiConfigService.requireCallableConfig(10L, 7L, "user-model"))
                .thenThrow(new BizException(AiErrorCode.MODEL_NOT_IN_CONFIG));

        assertThatThrownBy(() -> facade.chat(AiRequest.userConfig("hi", 10L, 7L, "user-model")))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.MODEL_NOT_IN_CONFIG.getCode());

        verify(factory, never()).createUserConfigProvider(any(), any(), any(), any(), any());
    }

    // ===== 指定平台模型：按模型路由、不轮转、不兜底、不记健康 =====

    @Test
    void modelRoutingSendsTheRequestedModelVerbatim() {
        when(pool.borrowForModel("glm-5.3")).thenReturn(first);
        when(first.chat(any(AiRequest.class))).thenReturn("answer");

        assertThat(facade.chat(AiRequest.model("hi", "glm-5.3"))).isEqualTo("answer");

        // 模型名随请求下发（由 Provider 拿去覆盖端点默认模型）
        ArgumentCaptor<AiRequest> captured = ArgumentCaptor.forClass(AiRequest.class);
        verify(first).chat(captured.capture());
        assertThat(captured.getValue().modelName()).isEqualTo("glm-5.3");
    }

    @Test
    void unknownModelFailsAsNotServedWithoutTouchingAnyProvider() {
        when(pool.borrowForModel("no-such-model")).thenReturn(null);

        assertThatThrownBy(() -> facade.chat(AiRequest.model("hi", "no-such-model")))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.MODEL_NOT_SERVED.getCode());

        // 平台没有这个模型：不发任何请求，也绝不退化成 AUTO 或别的端点的默认模型
        verify(first, never()).chat(any(AiRequest.class));
        verify(second, never()).chat(any(AiRequest.class));
        verify(pool, never()).reportFailure(any());
        verify(pool, never()).switchToNext();
    }

    @Test
    void modelRoutingFailureDoesNotPolluteEndpointHealth() {
        when(pool.borrowForModel("glm-5.3")).thenReturn(first);
        when(first.chat(any(AiRequest.class))).thenThrow(new IllegalStateException("model down"));

        assertThatThrownBy(() -> facade.chat(AiRequest.model("hi", "glm-5.3")))
                .isInstanceOf(BizException.class);

        // 把「某个模型在这个端点不可用」记成端点失败，三次就会把整个服务商从 AUTO 轮转里摘掉
        verify(pool, never()).reportFailure(any());
        verify(pool, never()).reportSuccess(any());
        verify(pool, never()).switchToNext();
    }

    @Test
    void unclassifiedModelFailureBecomesModelUnresponsiveWithCauseKept() {
        RuntimeException cause = new RuntimeException("socket reset");
        when(pool.borrowForModel("glm-5.3")).thenReturn(first);
        when(first.chat(any(AiRequest.class))).thenThrow(cause);

        BizException failure = (BizException) catchThrowable(() -> facade.chat(AiRequest.model("hi", "glm-5.3")));

        // 非业务异常换成"换个模型试试"，原始根因只进 cause 供排查
        assertThat(failure.getCode()).isEqualTo(AiErrorCode.MODEL_UNRESPONSIVE.getCode());
        assertThat(failure.getCause()).isSameAs(cause);
    }

    @Test
    void classifiedModelFailureIsPropagatedAsIs() {
        AiProviderHttpException classified = new AiProviderHttpException("GLM", 401, "bad key", null);
        when(pool.borrowForModel("glm-5.3")).thenReturn(first);
        when(first.chat(any(AiRequest.class))).thenThrow(classified);

        // 已分类的异常不能重包：把"密钥无效"说成"换个模型"会让用户白换一圈
        assertThat(catchThrowable(() -> facade.chat(AiRequest.model("hi", "glm-5.3"))))
                .isSameAs(classified);
    }

    @Test
    void clientErrorFromUpstreamOnTheModelPathSaysSwitchModel() {
        // 上游对"模型不存在/套餐不含"回 400（OpenCode Go 原话 Model is unavailable）。
        // 这条必须说成"换个模型"，而不是"AI 服务繁忙，请稍后重试"——后者会让人白重试。
        AiProviderHttpException unavailable =
                new AiProviderHttpException("opencode-go", 400, "Model is unavailable.", null);
        when(pool.borrowForModel("nope")).thenReturn(first);
        when(first.chat(any(AiRequest.class))).thenThrow(unavailable);

        BizException failure = (BizException) catchThrowable(() -> facade.chat(AiRequest.model("hi", "nope")));

        assertThat(failure.getCode()).isEqualTo(AiErrorCode.MODEL_UNRESPONSIVE.getCode());
        assertThat(failure.getCause()).isSameAs(unavailable);
    }

    @Test
    void authAndQuotaFailuresOnTheModelPathKeepTheirOwnCode() {
        when(pool.borrowForModel("m")).thenReturn(first);
        when(first.chat(any(AiRequest.class)))
                .thenThrow(new AiProviderHttpException("opencode-go", 401, "bad key", null));
        assertThat(((BizException) catchThrowable(() -> facade.chat(AiRequest.model("hi", "m")))).getCode())
                .isEqualTo(AiErrorCode.PROVIDER_KEY_INVALID.getCode());

        when(first.chat(any(AiRequest.class)))
                .thenThrow(new AiProviderHttpException("opencode-go", 429, "quota", "30"));
        assertThat(((BizException) catchThrowable(() -> facade.chat(AiRequest.model("hi", "m")))).getCode())
                .isEqualTo(AiErrorCode.PROVIDER_QUOTA_EXCEEDED.getCode());
    }

    @Test
    void modelRoutingOnStreamFollowsTheSameNoFallbackPolicy() {
        when(pool.borrowForModel("glm-5.3")).thenReturn(first);
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("partial");
            throw new IllegalStateException("model down");
        }).when(first).chatStream(any(AiRequest.class), any());

        List<String> received = new ArrayList<>();
        assertThatThrownBy(() -> facade.chatStream(AiRequest.model("hi", "glm-5.3"), received::add))
                .isInstanceOf(BizException.class);

        assertThat(received).containsExactly("partial");
        verify(pool, never()).switchToNext();
        verify(second, never()).chatStream(any(AiRequest.class), any());
    }

    @Test
    void platformModelsDelegatesToThePool() {
        when(pool.platformModels()).thenReturn(List.of("a", "b"));

        assertThat(facade.platformModels()).containsExactly("a", "b");
    }

    private AiProvider provider(String name) {
        AiProvider provider = org.mockito.Mockito.mock(AiProvider.class);
        lenient().when(provider.name()).thenReturn(name);
        return provider;
    }
}
