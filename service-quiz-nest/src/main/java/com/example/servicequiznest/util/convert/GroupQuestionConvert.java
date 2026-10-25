package com.example.servicequiznest.util.convert;

import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.question.UpdateGroupQuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;

/**
 * 小组题目转换：本服务 DTO 与 service-api 题目 DTO 之间的映射。
 *
 * <p>方法名统一用「源对象To目标对象」表达方向，调用点不需要再猜字段流向。
 * 转换只做字段搬运，不复制业务校验；校验统一放在 {@code util/check} 中。</p>
 */
public final class GroupQuestionConvert {

    private GroupQuestionConvert() {
    }

    /**
     * 自建题目新增入参转换为题目服务建题入参。
     *
     * <p>只映射出题字段；{@code questionId/status/createUserId} 等由题目服务生成或控制，
     * 不能由调用方指定。</p>
     *
     * @param dto 小组题目新增入参
     * @return 题目服务建题入参，题目 id/状态/创建人保持为空
     * @throws BizException 入参为空时抛出
     */
    public static QuestionDto createGroupQuestionDtoToQuestionDto(CreateGroupQuestionDto dto) {
        if (dto == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        return QuestionDto.builder()
                .title(dto.getTitle())
                .description(dto.getDescription())
                .inputDesc(dto.getInputDesc())
                .outputDesc(dto.getOutputDesc())
                .sampleInput(dto.getSampleInput())
                .sampleOutput(dto.getSampleOutput())
                .hint(dto.getHint())
                .tags(dto.getTags())
                .timeLimit(dto.getTimeLimit())
                .memoryLimit(dto.getMemoryLimit())
                .build();
    }

    /**
     * 小组修改题目 DTO 转换为 service-question 内部改题入参。
     *
     * <p>两个 DTO 同名，因此源类型使用全限定名。目标 DTO 里的 {@code questionId}
     * 必须由调用方显式传入（来自小组索引），不能使用请求体里的值，避免调用方
     * 通过篡改 questionId 修改其他题目。</p>
     *
     * @param dto        小组修改题目请求体
     * @param questionId 小组索引对应的真实题目 id
     * @return service-api 不可变修改 DTO
     * @throws BizException 入参为空或题目 id 非法时抛出
     */
    public static UpdateGroupQuestionDto quizUpdateGroupQuestionDtoToApiUpdateGroupQuestionDto(
            com.example.servicequiznest.dto.UpdateGroupQuestionDto dto, Long questionId) {
        if (dto == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        if (questionId == null || questionId <= 0) {
            throw new BizException(QuizNestErrorCode.INVALID_QUESTION_ID);
        }
        return new UpdateGroupQuestionDto(
                questionId,
                dto.getTitle(),
                dto.getDescription(),
                dto.getInputDesc(),
                dto.getOutputDesc(),
                dto.getSampleInput(),
                dto.getSampleOutput(),
                dto.getHint(),
                dto.getTags(),
                dto.getTimeLimit(),
                dto.getMemoryLimit()
        );
    }
}
