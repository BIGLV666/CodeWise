package com.example.servicequiznest.util.convert;

import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.dto.UpdateGroupQuestionDto;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * util/convert 包的字段映射契约：转换不能丢字段，也不能让调用方决定目标题目 id。
 */
class GroupQuestionConvertTest {

    @Test
    @DisplayName("建题转换：全部出题字段一一映射，题目 id/状态/创建人保持为空")
    void createDtoToQuestionDtoMapsAllFields() {
        CreateGroupQuestionDto dto = CreateGroupQuestionDto.builder()
                .groupId(1L)
                .questionId(999L)
                .title("两数之和")
                .description("描述")
                .inputDesc("输入")
                .outputDesc("输出")
                .sampleInput("1 2")
                .sampleOutput("3")
                .hint("提示")
                .tags("数学,入门")
                .timeLimit(1000)
                .memoryLimit(256)
                .build();

        QuestionDto target = GroupQuestionConvert.createGroupQuestionDtoToQuestionDto(dto);

        assertThat(target.getTitle()).isEqualTo("两数之和");
        assertThat(target.getDescription()).isEqualTo("描述");
        assertThat(target.getInputDesc()).isEqualTo("输入");
        assertThat(target.getOutputDesc()).isEqualTo("输出");
        assertThat(target.getSampleInput()).isEqualTo("1 2");
        assertThat(target.getSampleOutput()).isEqualTo("3");
        assertThat(target.getHint()).isEqualTo("提示");
        assertThat(target.getTags()).isEqualTo("数学,入门");
        assertThat(target.getTimeLimit()).isEqualTo(1000);
        assertThat(target.getMemoryLimit()).isEqualTo(256);
        // questionId 是平台题引用字段，不能带进建题请求
        assertThat(target.getQuestionId()).isNull();
        assertThat(target.getStatus()).isNull();
        assertThat(target.getCreateUserId()).isNull();
    }

    @Test
    @DisplayName("建题转换：空对象按参数缺失拒绝")
    void createDtoToQuestionDtoRejectsNull() {
        assertThatThrownBy(() -> GroupQuestionConvert.createGroupQuestionDtoToQuestionDto(null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(CommonErrorCode.PARAM_MISSING.getCode());
    }

    @Test
    @DisplayName("改题转换：目标 questionId 取方法参数，忽略请求体里的 questionId")
    void updateDtoToApiDtoUsesTrustedQuestionId() {
        UpdateGroupQuestionDto dto = UpdateGroupQuestionDto.builder()
                .questionId(999L)
                .title("改后标题")
                .description("改后描述")
                .inputDesc("输入")
                .outputDesc("输出")
                .sampleInput("1 2")
                .sampleOutput("3")
                .hint("提示")
                .tags("数学")
                .timeLimit(2000)
                .memoryLimit(512)
                .build();

        org.example.serviceapi.dto.question.UpdateGroupQuestionDto target =
                GroupQuestionConvert.quizUpdateGroupQuestionDtoToApiUpdateGroupQuestionDto(dto, 7L);

        assertThat(target.questionId()).isEqualTo(7L);
        assertThat(target.title()).isEqualTo("改后标题");
        assertThat(target.description()).isEqualTo("改后描述");
        assertThat(target.inputDesc()).isEqualTo("输入");
        assertThat(target.outputDesc()).isEqualTo("输出");
        assertThat(target.sampleInput()).isEqualTo("1 2");
        assertThat(target.sampleOutput()).isEqualTo("3");
        assertThat(target.hint()).isEqualTo("提示");
        assertThat(target.tags()).isEqualTo("数学");
        assertThat(target.timeLimit()).isEqualTo(2000);
        assertThat(target.memoryLimit()).isEqualTo(512);
    }

    @Test
    @DisplayName("改题转换：空入参/非法题目 id 拒绝")
    void updateDtoToApiDtoRejectsInvalidInput() {
        UpdateGroupQuestionDto dto = UpdateGroupQuestionDto.builder().title("t").build();
        assertThatThrownBy(() -> GroupQuestionConvert.quizUpdateGroupQuestionDtoToApiUpdateGroupQuestionDto(null, 7L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(CommonErrorCode.PARAM_MISSING.getCode());
        assertThatThrownBy(() -> GroupQuestionConvert.quizUpdateGroupQuestionDtoToApiUpdateGroupQuestionDto(dto, null))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuizNestErrorCode.INVALID_QUESTION_ID.getCode());
        assertThatThrownBy(() -> GroupQuestionConvert.quizUpdateGroupQuestionDtoToApiUpdateGroupQuestionDto(dto, 0L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(QuizNestErrorCode.INVALID_QUESTION_ID.getCode());
    }
}
