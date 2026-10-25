package org.example.servicequestion.Feign;

import io.github.biglv666.apigovernance.annotation.Skip;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.question.UpdateGroupQuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicequestion.FeugnService.QuestionServiceFeign;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 内部建题端点鉴权边界：没有正确 X-CodeWise-Internal-Token 一律拒绝。 */
@ExtendWith(MockitoExtension.class)
class QuestionFeginTest {

    private static final String INTERNAL_TOKEN = "internal-secret";

    @Mock
    private QuestionServiceFeign questionServiceFeign;

    @Test
    void rejectsForgedInternalToken() {
        assertThatThrownBy(() -> controller().addQuestion("wrong-token", new QuestionDto()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuestionErrorCode.INTERNAL_CALL_UNAUTHORIZED.getCode());
        verifyNoInteractions(questionServiceFeign);
    }

    @Test
    void delegatesWithValidInternalToken() {
        QuestionDto created = QuestionDto.builder().questionId(9L).build();
        when(questionServiceFeign.addQuestion(any(QuestionDto.class))).thenReturn(created);

        Result<QuestionDto> result = controller().addQuestion(INTERNAL_TOKEN, new QuestionDto());

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isSameAs(created);
        verify(questionServiceFeign).addQuestion(any(QuestionDto.class));
    }

    @Test
    void internalAddRouteIsRateLimitSkipped() throws NoSuchMethodException {
        Method method = QuestionFegin.class.getMethod("addQuestion", String.class, QuestionDto.class);
        assertThat(method.isAnnotationPresent(Skip.class)).isTrue();
    }

    @Test
    void feignContractMatchesInternalEndpoint() throws NoSuchMethodException {
        PostMapping feignMapping = QuestionFeignClient.class
                .getMethod("addQuestion", String.class, QuestionDto.class)
                .getAnnotation(PostMapping.class);
        PostMapping controllerMapping = QuestionFegin.class
                .getMethod("addQuestion", String.class, QuestionDto.class)
                .getAnnotation(PostMapping.class);
        RequestMapping classMapping = QuestionFegin.class.getAnnotation(RequestMapping.class);

        assertThat(feignMapping).isNotNull();
        assertThat(feignMapping.value()).containsExactly("/api/question/internal/add");
        assertThat(controllerMapping.value()).containsExactly("/internal/add");
        assertThat(classMapping.value()).containsExactly("/api/question");
    }

    @Test
    void feignContractMatchesDeleteEndpoint() throws NoSuchMethodException {
        DeleteMapping feignMapping = QuestionFeignClient.class
                .getMethod("deleteQuestion", String.class, Long.class)
                .getAnnotation(DeleteMapping.class);
        // 控制器方法是包级可见（避免被外部直接调用），必须用 getDeclaredMethod
        DeleteMapping controllerMapping = QuestionFegin.class
                .getDeclaredMethod("deleteQuestion", String.class, Long.class)
                .getAnnotation(DeleteMapping.class);
        RequestMapping classMapping = QuestionFegin.class.getAnnotation(RequestMapping.class);

        assertThat(feignMapping).isNotNull();
        assertThat(feignMapping.value()).containsExactly("/api/question/internal/delete");
        assertThat(controllerMapping.value()).containsExactly("/internal/delete");
        assertThat(classMapping.value()).containsExactly("/api/question");
        assertThat(classMapping.value()[0] + controllerMapping.value()[0])
                .as("类级路径 + 方法级路径必须与 Feign 契约一致")
                .isEqualTo(feignMapping.value()[0]);
    }

    @Test
    void deleteRejectsForgedInternalToken() {
        assertThatThrownBy(() -> controller().deleteQuestion("wrong-token", 9L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuestionErrorCode.INTERNAL_CALL_UNAUTHORIZED.getCode());
        verifyNoInteractions(questionServiceFeign);
    }

    @Test
    void deleteDelegatesWithValidInternalToken() {
        Result<String> result = controller().deleteQuestion(INTERNAL_TOKEN, 9L);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getMessage()).isEqualTo("success");
        verify(questionServiceFeign).deleteQuestion(9L);
    }

    @Test
    void updateRejectsForgedInternalToken() {
        assertThatThrownBy(() -> controller().updateQuestion("wrong-token", updateRequest()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuestionErrorCode.INTERNAL_CALL_UNAUTHORIZED.getCode());
        verifyNoInteractions(questionServiceFeign);
    }

    @Test
    void updateDelegatesWithValidInternalToken() {
        QuestionDto updated = QuestionDto.builder().questionId(9L).title("改后标题").build();
        when(questionServiceFeign.updateGroupQuestion(any(UpdateGroupQuestionDto.class))).thenReturn(updated);

        Result<QuestionDto> result = controller().updateQuestion(INTERNAL_TOKEN, updateRequest());

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isSameAs(updated);
        verify(questionServiceFeign).updateGroupQuestion(any(UpdateGroupQuestionDto.class));
    }

    @Test
    void internalPutRouteIsRateLimitSkipped() throws NoSuchMethodException {
        Method method = QuestionFegin.class.getMethod(
                "updateQuestion", String.class, UpdateGroupQuestionDto.class);
        assertThat(method.isAnnotationPresent(Skip.class)).isTrue();
    }

    @Test
    void feignContractMatchesPutEndpoint() throws NoSuchMethodException {
        PutMapping feignMapping = QuestionFeignClient.class
                .getMethod("putQuestion", String.class, UpdateGroupQuestionDto.class)
                .getAnnotation(PutMapping.class);
        PutMapping controllerMapping = QuestionFegin.class
                .getMethod("updateQuestion", String.class, UpdateGroupQuestionDto.class)
                .getAnnotation(PutMapping.class);
        RequestMapping classMapping = QuestionFegin.class.getAnnotation(RequestMapping.class);

        assertThat(feignMapping).isNotNull();
        assertThat(feignMapping.value()).containsExactly("/api/question/internal/put");
        assertThat(controllerMapping.value()).containsExactly("/internal/put");
        assertThat(classMapping.value()).containsExactly("/api/question");
        assertThat(classMapping.value()[0] + controllerMapping.value()[0])
                .as("类级路径 + 方法级路径必须与 Feign 契约一致")
                .isEqualTo(feignMapping.value()[0]);
    }

    @Test
    void feignSendsInternalTokenHeader() throws NoSuchMethodException {
        Method method = QuestionFeignClient.class.getMethod("addQuestion", String.class, QuestionDto.class);

        assertThat(method.getParameterAnnotations()[0])
                .anyMatch(annotation -> annotation instanceof RequestHeader header
                        && "X-CodeWise-Internal-Token".equals(header.value()));
    }

    private QuestionFegin controller() {
        return new QuestionFegin(questionServiceFeign, INTERNAL_TOKEN);
    }

    private static UpdateGroupQuestionDto updateRequest() {
        return new UpdateGroupQuestionDto(
                9L, "改后标题", "改后描述", "输入", "输出", "1 2", "3", "提示", "数学", 2000, 512);
    }
}
