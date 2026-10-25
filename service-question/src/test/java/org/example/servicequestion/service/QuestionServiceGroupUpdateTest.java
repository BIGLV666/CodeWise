package org.example.servicequestion.service;

import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.ErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicequestion.dto.InsertQuestionDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 服务间改题入口 {@link QuestionService#updateGroupQuestion(InsertQuestionDto, Long)}。
 *
 * <p>该入口不做管理员校验，因此必须自己守住两条边界：只能改 {@code status=3}
 * 的小组自建题；正文变化后 content_hash 要同步更新，唯一键冲突转成业务错误。</p>
 */
@ExtendWith(MockitoExtension.class)
class QuestionServiceGroupUpdateTest {

    private static final Long QUESTION_ID = 7L;

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SubmitRecordMapper submitRecordMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private UserFeignClient userFeignClient;
    @Mock
    private FunctionConfigMapper functionConfigMapper;

    @InjectMocks
    private QuestionService questionService;

    @Test
    @DisplayName("题目 id 为空：参数错误，不查库")
    void nullQuestionIdRejected() {
        assertCode(QuestionErrorCode.QUESTION_ID_REQUIRED,
                () -> questionService.updateGroupQuestion(validDto(), null));
        verifyNoInteractions(questionMapper);
    }

    @Test
    @DisplayName("入参不合法：复用普通题目校验，不查库")
    void invalidDtoRejected() {
        InsertQuestionDto dto = validDto();
        dto.setTitle(" ");
        assertCode(QuestionErrorCode.QUESTION_TITLE_REQUIRED,
                () -> questionService.updateGroupQuestion(dto, QUESTION_ID));
        verifyNoInteractions(questionMapper);
    }

    @Test
    @DisplayName("题目不存在：返回未找到")
    void missingQuestionRejected() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(null);

        assertCode(QuestionErrorCode.QUESTION_NOT_FOUND,
                () -> questionService.updateGroupQuestion(validDto(), QUESTION_ID));
    }

    @Test
    @DisplayName("非私密题（平台公开/审核/下架）：拒绝修改")
    void nonGroupQuestionRejected() {
        for (Integer status : new Integer[]{0, 1, 2, null}) {
            Question question = question(status);
            when(questionMapper.selectById(QUESTION_ID)).thenReturn(question);

            assertCode(QuestionErrorCode.GROUP_QUESTION_UPDATE_FORBIDDEN,
                    () -> questionService.updateGroupQuestion(validDto(), QUESTION_ID));
        }
    }

    @Test
    @DisplayName("成功：字段全量覆盖、content_hash 与更新时间同步")
    void updatesAllFieldsAndContentHash() {
        Question question = question(3);
        question.setContentHash("old-hash");
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question);
        when(questionMapper.updateById(any(Question.class))).thenReturn(1);

        Question updated = questionService.updateGroupQuestion(validDto(), QUESTION_ID);

        assertThat(updated.getTitle()).isEqualTo("改后标题");
        assertThat(updated.getDescription()).isEqualTo("改后描述");
        assertThat(updated.getInputDesc()).isEqualTo("输入");
        assertThat(updated.getOutputDesc()).isEqualTo("输出");
        assertThat(updated.getSampleInput()).isEqualTo("1 2");
        assertThat(updated.getSampleOutput()).isEqualTo("3");
        assertThat(updated.getHint()).isEqualTo("提示");
        assertThat(updated.getTags()).isEqualTo("数学");
        assertThat(updated.getTimeLimit()).isEqualTo(2000);
        assertThat(updated.getMemoryLimit()).isEqualTo(512);
        assertThat(updated.getContentHash()).isNotEqualTo("old-hash");
        assertThat(updated.getUpdateTime()).isNotNull();
        verify(questionMapper).updateById(question);
    }

    @Test
    @DisplayName("更新 0 行：返回更新失败")
    void zeroRowsReturnsUpdateFailed() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question(3));
        when(questionMapper.updateById(any(Question.class))).thenReturn(0);

        assertCode(QuestionErrorCode.QUESTION_UPDATE_FAILED,
                () -> questionService.updateGroupQuestion(validDto(), QUESTION_ID));
    }

    @Test
    @DisplayName("正文重复触发唯一键：转成业务错误，不泄露 SQL")
    void duplicateContentMappedToBusinessError() {
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question(3));
        when(questionMapper.updateById(any(Question.class)))
                .thenThrow(new DuplicateKeyException("uk_id_hash"));

        assertCode(QuestionErrorCode.QUESTION_CONTENT_DUPLICATED,
                () -> questionService.updateGroupQuestion(validDto(), QUESTION_ID));
    }

    private static Question question(Integer status) {
        return Question.builder()
                .questionId(QUESTION_ID)
                .title("旧标题")
                .description("旧描述")
                .status(status)
                .createUserId(10L)
                .build();
    }

    private static InsertQuestionDto validDto() {
        InsertQuestionDto dto = new InsertQuestionDto();
        dto.setTitle("改后标题");
        dto.setDescription("改后描述");
        dto.setInputDesc("输入");
        dto.setOutputDesc("输出");
        dto.setSampleInput("1 2");
        dto.setSampleOutput("3");
        dto.setHint("提示");
        dto.setTags("数学");
        dto.setTimeLimit(2000);
        dto.setMemoryLimit(512);
        return dto;
    }

    private static void assertCode(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }
}
