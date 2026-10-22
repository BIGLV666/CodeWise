package org.example.serviceai.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceai.entry.UserAiConfig;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.mapper.UserAiConfigMapper;
import org.example.serviceai.service.ApiKeyCryptoService;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户自带配置的「可调用性」校验——这是 AI 链路里唯一的 SSRF 边界，
 * 服务端会真的按用户填的地址发请求。
 *
 * <p>改造前这些逻辑散在 {@code UserAiService} 里且<b>零测试覆盖</b>；搬家时掉一条
 * 都没人会发现，所以这里逐条把它钉住。</p>
 *
 * <p>用字面量公网 IP 而非域名：既避开真实 DNS 依赖，也保证「公网地址放行、
 * 内网地址拦截」两侧都有正例反例。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserAiConfigServiceTest {

    private static final String PUBLIC_HOST = "93.184.216.34";
    private static final Long USER_ID = 10L;
    private static final Long CONFIG_ID = 7L;
    private static final String MODEL = "gpt-4o-mini";

    @Mock
    private UserAiConfigMapper userAiConfigMapper;
    @Mock
    private ApiKeyCryptoService apiKeyCryptoService;

    private UserAiConfigService service;

    @BeforeEach
    void setUp() {
        service = new UserAiConfigService(userAiConfigMapper, apiKeyCryptoService, new ObjectMapper());
        lenient().when(apiKeyCryptoService.decrypt("encrypted-key")).thenReturn("plain-key");
    }

    @Test
    void returnsNormalizedEndpointDecryptedKeyAndModel() {
        ownedConfig("https://" + PUBLIC_HOST + "/v1");

        UserAiCall call = service.requireCallableConfig(USER_ID, CONFIG_ID, MODEL);

        assertThat(call.configId()).isEqualTo(CONFIG_ID);
        assertThat(call.endpointUrl()).isEqualTo("https://" + PUBLIC_HOST + "/v1/chat/completions");
        assertThat(call.apiKey()).isEqualTo("plain-key");
        assertThat(call.model()).isEqualTo(MODEL);
        assertThat(call.timeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void baseUrlAlreadyEndingWithChatCompletionsIsNotDoubled() {
        ownedConfig("https://" + PUBLIC_HOST + "/v1/chat/completions");

        assertThat(service.requireCallableConfig(USER_ID, CONFIG_ID, MODEL).endpointUrl())
                .isEqualTo("https://" + PUBLIC_HOST + "/v1/chat/completions");
    }

    @Test
    void trailingSlashesAreStripped() {
        ownedConfig("https://" + PUBLIC_HOST + "/v1///");

        assertThat(service.requireCallableConfig(USER_ID, CONFIG_ID, MODEL).endpointUrl())
                .isEqualTo("https://" + PUBLIC_HOST + "/v1/chat/completions");
    }

    @Test
    void missingUserIdFailsAsLoginRequired() {
        assertThatThrownBy(() -> service.requireCallableConfig(null, CONFIG_ID, MODEL))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(CommonErrorCode.LOGIN_REQUIRED.getCode());
    }

    @Test
    void missingConfigIdFailsAsConfigIdRequired() {
        assertThatThrownBy(() -> service.requireCallableConfig(USER_ID, null, MODEL))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.CONFIG_ID_REQUIRED.getCode());
    }

    @Test
    void configOwnedBySomeoneElseIsNotAccessible() {
        // 查询条件里带了 userId，别人的配置查不出来 → 与「不存在」同一处理
        when(userAiConfigMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.requireCallableConfig(USER_ID, CONFIG_ID, MODEL))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.CONFIG_NOT_ACCESSIBLE.getCode());
    }

    @Test
    void modelOutsideTheConfigIsRejected() {
        ownedConfig("https://" + PUBLIC_HOST + "/v1");

        assertThatThrownBy(() -> service.requireCallableConfig(USER_ID, CONFIG_ID, "not-configured"))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.MODEL_NOT_IN_CONFIG.getCode());
    }

    @Test
    void blankModelNameIsRejected() {
        ownedConfig("https://" + PUBLIC_HOST + "/v1");

        assertThatThrownBy(() -> service.requireCallableConfig(USER_ID, CONFIG_ID, "  "))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(AiErrorCode.MODEL_NAME_REQUIRED.getCode());
    }

    @Test
    void plainHttpEndpointIsRejected() {
        ownedConfig("http://" + PUBLIC_HOST + "/v1");

        expectRejected(AiErrorCode.AI_URL_HTTPS_REQUIRED);
    }

    @Test
    void endpointWithQueryStringIsRejected() {
        ownedConfig("https://" + PUBLIC_HOST + "/v1?token=abc");

        expectRejected(AiErrorCode.AI_URL_NO_QUERY);
    }

    @Test
    void loopbackEndpointIsRejected() {
        ownedConfig("https://127.0.0.1/v1");

        expectRejected(AiErrorCode.AI_URL_INTERNAL_FORBIDDEN);
    }

    @Test
    void localhostNameIsRejected() {
        ownedConfig("https://localhost/v1");

        expectRejected(AiErrorCode.AI_URL_INTERNAL_FORBIDDEN);
    }

    @Test
    void privateNetworkEndpointIsRejected() {
        ownedConfig("https://10.0.0.1/v1");

        expectRejected(AiErrorCode.AI_URL_INTERNAL_FORBIDDEN);
    }

    @Test
    void linkLocalEndpointIsRejected() {
        ownedConfig("https://169.254.169.254/latest/meta-data");

        expectRejected(AiErrorCode.AI_URL_INTERNAL_FORBIDDEN);
    }

    @Test
    void endpointWithCredentialsInUrlIsRejected() {
        ownedConfig("https://user:pass@" + PUBLIC_HOST + "/v1");

        expectRejected(AiErrorCode.AI_URL_INVALID);
    }

    @Test
    void blankEndpointIsRejected() {
        ownedConfig("   ");

        expectRejected(AiErrorCode.AI_URL_REQUIRED);
    }

    /** 校验发生在解密之前：地址不合法时不该去碰密钥。 */
    @Test
    void invalidEndpointDoesNotDecryptTheKey() {
        ownedConfig("https://127.0.0.1/v1");

        expectRejected(AiErrorCode.AI_URL_INTERNAL_FORBIDDEN);

        verify(apiKeyCryptoService, never()).decrypt(any());
    }

    private void expectRejected(AiErrorCode expected) {
        assertThatThrownBy(() -> service.requireCallableConfig(USER_ID, CONFIG_ID, MODEL))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(expected.getCode());
    }

    private void ownedConfig(String aiUrl) {
        UserAiConfig config = UserAiConfig.builder()
                .userAiConfigId(CONFIG_ID)
                .userId(USER_ID)
                .groupName("g")
                .modelNames(List.of(MODEL))
                .aiUrl(aiUrl)
                .apiKey("encrypted-key")
                .build();
        lenient().when(userAiConfigMapper.selectOne(any())).thenReturn(config);
    }
}
