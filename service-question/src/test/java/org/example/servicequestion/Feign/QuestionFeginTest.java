package org.example.servicequestion.Feign;

import io.github.biglv666.apigovernance.annotation.Skip;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicequestion.FeugnService.QuestionServiceFeign;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;
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
    void feignSendsInternalTokenHeader() throws NoSuchMethodException {
        Method method = QuestionFeignClient.class.getMethod("addQuestion", String.class, QuestionDto.class);

        assertThat(method.getParameterAnnotations()[0])
                .anyMatch(annotation -> annotation instanceof RequestHeader header
                        && "X-CodeWise-Internal-Token".equals(header.value()));
    }

    private QuestionFegin controller() {
        return new QuestionFegin(questionServiceFeign, INTERNAL_TOKEN);
    }
}
