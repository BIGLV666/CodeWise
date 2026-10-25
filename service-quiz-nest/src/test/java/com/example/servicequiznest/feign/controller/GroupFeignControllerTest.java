package com.example.servicequiznest.feign.controller;

import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.feign.services.GroupFeignService;
import io.github.biglv666.apigovernance.annotation.Skip;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.GroupFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 小组内部 Feign 端点契约：路径必须与 {@link GroupFeignClient} 一致，
 * 且没有正确的 {@code X-CodeWise-Internal-Token} 一律拒绝。
 */
@ExtendWith(MockitoExtension.class)
class GroupFeignControllerTest {

    private static final String INTERNAL_TOKEN = "internal-secret";
    private static final long QUESTION_ID = 7L;

    @Mock
    private GroupFeignService groupFeignService;

    private GroupFeignController controller;

    @BeforeEach
    void setUp() {
        controller = new GroupFeignController(groupFeignService, INTERNAL_TOKEN);
    }

    @Test
    @DisplayName("成员校验：伪造内部 Token 拒绝，不调用业务")
    void memberRejectsForgedToken() {
        assertThatThrownBy(() -> controller.checkIsGroupMember("wrong-token", QUESTION_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuizNestErrorCode.INTERNAL_CALL_UNAUTHORIZED.getCode());
        verifyNoInteractions(groupFeignService);
    }

    @Test
    @DisplayName("管理员校验：伪造内部 Token 拒绝，不调用业务")
    void adminRejectsForgedToken() {
        assertThatThrownBy(() -> controller.checkIsGroupAdmin("wrong-token", QUESTION_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuizNestErrorCode.INTERNAL_CALL_UNAUTHORIZED.getCode());
        verifyNoInteractions(groupFeignService);
    }

    @Test
    @DisplayName("成员校验：合法 Token 委托业务并返回布尔结果")
    void memberDelegatesWithValidToken() {
        when(groupFeignService.checkIsGroupMember(QUESTION_ID)).thenReturn(true);

        Result<Boolean> result = controller.checkIsGroupMember(INTERNAL_TOKEN, QUESTION_ID);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isTrue();
        verify(groupFeignService).checkIsGroupMember(QUESTION_ID);
    }

    @Test
    @DisplayName("管理员校验：合法 Token 委托业务并返回布尔结果")
    void adminDelegatesWithValidToken() {
        when(groupFeignService.checkIsGroupAdmin(QUESTION_ID)).thenReturn(false);

        Result<Boolean> result = controller.checkIsGroupAdmin(INTERNAL_TOKEN, QUESTION_ID);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isFalse();
        verify(groupFeignService).checkIsGroupAdmin(QUESTION_ID);
    }

    @Test
    @DisplayName("Feign 契约：类级路径 + 方法级路径与 service-api 完全一致")
    void feignContractMatchesControllerPaths() throws NoSuchMethodException {
        RequestMapping classMapping = GroupFeignController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping.value()).containsExactly("/api/quiz/internal");

        assertPath("checkIsGroupMember", "/questions/{questionId}/member",
                "/api/quiz/internal/questions/{questionId}/member");
        assertPath("checkIsGroupAdmin", "/questions/{questionId}/admin",
                "/api/quiz/internal/questions/{questionId}/admin");
    }

    @Test
    @DisplayName("内部端点不参与用户维度限流")
    void routesAreRateLimitSkipped() throws NoSuchMethodException {
        assertThat(GroupFeignController.class
                .getMethod("checkIsGroupMember", String.class, Long.class)
                .isAnnotationPresent(Skip.class)).isTrue();
        assertThat(GroupFeignController.class
                .getMethod("checkIsGroupAdmin", String.class, Long.class)
                .isAnnotationPresent(Skip.class)).isTrue();
    }

    @Test
    @DisplayName("Feign 契约：服务名与内部 Token 请求头正确")
    void feignClientTargetsQuizNestAndSendsInternalTokenHeader() throws NoSuchMethodException {
        FeignClient feignClient = GroupFeignClient.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.name()).isEqualTo("service-quiz-nest");

        for (String methodName : new String[]{"checkIsGroupMember", "checkIsGroupAdmin"}) {
            Method method = GroupFeignClient.class.getMethod(methodName, String.class, Long.class);
            assertThat(method.getParameterAnnotations()[0])
                    .anyMatch(annotation -> annotation instanceof RequestHeader header
                            && "X-CodeWise-Internal-Token".equals(header.value()));
        }
    }

    private static void assertPath(String methodName, String controllerPath, String feignPath)
            throws NoSuchMethodException {
        GetMapping controllerMapping = GroupFeignController.class
                .getMethod(methodName, String.class, Long.class)
                .getAnnotation(GetMapping.class);
        GetMapping feignMapping = GroupFeignClient.class
                .getMethod(methodName, String.class, Long.class)
                .getAnnotation(GetMapping.class);
        RequestMapping classMapping = GroupFeignController.class.getAnnotation(RequestMapping.class);

        assertThat(controllerMapping).isNotNull();
        assertThat(controllerMapping.value()).containsExactly(controllerPath);
        assertThat(feignMapping).isNotNull();
        assertThat(feignMapping.value()).containsExactly(feignPath);
        assertThat(classMapping.value()[0] + controllerMapping.value()[0])
                .as("类级路径 + 方法级路径必须与 Feign 契约一致")
                .isEqualTo(feignMapping.value()[0]);
    }
}
