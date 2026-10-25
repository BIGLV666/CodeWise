package org.example.servicequestion.FeugnService;

import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.question.UpdateGroupQuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.servicequestion.dto.InsertQuestionDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.example.servicequestion.service.QuestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Feign 建题入口单测：只做 DTO 映射并委托 {@link QuestionService}，
 * 不复制校验/锁/状态设置（否则两条建题路径会逐渐分叉）。
 */
@ExtendWith(MockitoExtension.class)
class QuestionServiceFeignTest {

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private QuestionService questionService;
    @Mock
    private TestCaseMapper testCaseMapper;

    @Test
    void addQuestionDelegatesToQuestionService() {
        QuestionDto request = QuestionDto.builder()
                .title("两数之和")
                .description("给定两个整数，输出它们的和")
                .inputDesc("两个整数")
                .outputDesc("它们的和")
                .sampleInput("1 2")
                .sampleOutput("3")
                .hint("直接相加")
                .tags("数组")
                .timeLimit(1000)
                .memoryLimit(256)
                .build();
        Question saved = Question.builder()
                .questionId(5L)
                .title("两数之和")
                .description("给定两个整数，输出它们的和")
                .status(3)
                .timeLimit(1000)
                .memoryLimit(256)
                .build();
        when(questionService.addQuestion(any(InsertQuestionDto.class))).thenReturn(saved);

        QuestionDto result = new QuestionServiceFeign(questionMapper, questionService,testCaseMapper).addQuestion(request);

        ArgumentCaptor<InsertQuestionDto> captor = ArgumentCaptor.forClass(InsertQuestionDto.class);
        verify(questionService).addQuestion(captor.capture());
        InsertQuestionDto insert = captor.getValue();
        assertThat(insert.getTitle()).isEqualTo("两数之和");
        assertThat(insert.getDescription()).isEqualTo("给定两个整数，输出它们的和");
        assertThat(insert.getInputDesc()).isEqualTo("两个整数");
        assertThat(insert.getOutputDesc()).isEqualTo("它们的和");
        assertThat(insert.getSampleInput()).isEqualTo("1 2");
        assertThat(insert.getSampleOutput()).isEqualTo("3");
        assertThat(insert.getHint()).isEqualTo("直接相加");
        assertThat(insert.getTags()).isEqualTo("数组");
        assertThat(insert.getTimeLimit()).isEqualTo(1000);
        assertThat(insert.getMemoryLimit()).isEqualTo(256);

        assertThat(result.getQuestionId()).isEqualTo(5L);
        assertThat(result.getStatus()).isEqualTo(3);
        verifyNoInteractions(questionMapper);
    }

    @Test
    void nullRequestRejected() {
        assertThatThrownBy(() -> new QuestionServiceFeign(questionMapper, questionService,testCaseMapper).addQuestion(null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuestionErrorCode.QUESTION_SAVE_FAILED.getCode());
        verifyNoInteractions(questionService, questionMapper);
    }

    @Test
    void updateQuestionMapsRecordToInsertDtoAndDelegates() {
        UpdateGroupQuestionDto request = new UpdateGroupQuestionDto(
                7L, "改后标题", "改后描述", "输入", "输出", "1 2", "3", "提示", "数学", 2000, 512);
        Question updated = Question.builder()
                .questionId(7L)
                .title("改后标题")
                .description("改后描述")
                .status(3)
                .timeLimit(2000)
                .memoryLimit(512)
                .build();
        when(questionService.updateGroupQuestion(any(InsertQuestionDto.class), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(updated);

        QuestionDto result = new QuestionServiceFeign(questionMapper, questionService, testCaseMapper)
                .updateGroupQuestion(request);

        ArgumentCaptor<InsertQuestionDto> captor = ArgumentCaptor.forClass(InsertQuestionDto.class);
        verify(questionService).updateGroupQuestion(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        InsertQuestionDto insert = captor.getValue();
        assertThat(insert.getTitle()).isEqualTo("改后标题");
        assertThat(insert.getDescription()).isEqualTo("改后描述");
        assertThat(insert.getInputDesc()).isEqualTo("输入");
        assertThat(insert.getOutputDesc()).isEqualTo("输出");
        assertThat(insert.getSampleInput()).isEqualTo("1 2");
        assertThat(insert.getSampleOutput()).isEqualTo("3");
        assertThat(insert.getHint()).isEqualTo("提示");
        assertThat(insert.getTags()).isEqualTo("数学");
        assertThat(insert.getTimeLimit()).isEqualTo(2000);
        assertThat(insert.getMemoryLimit()).isEqualTo(512);
        assertThat(result.getQuestionId()).isEqualTo(7L);
        verifyNoInteractions(questionMapper);
    }

    @Test
    void updateQuestionRejectsNullRequestOrMissingQuestionId() {
        assertThatThrownBy(() -> new QuestionServiceFeign(questionMapper, questionService, testCaseMapper)
                .updateGroupQuestion(null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuestionErrorCode.QUESTION_ID_REQUIRED.getCode());
        assertThatThrownBy(() -> new QuestionServiceFeign(questionMapper, questionService, testCaseMapper)
                .updateGroupQuestion(new UpdateGroupQuestionDto(
                        null, "t", "d", null, null, null, null, null, null, 1, 1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuestionErrorCode.QUESTION_ID_REQUIRED.getCode());
        verifyNoInteractions(questionService, questionMapper);
    }
}
