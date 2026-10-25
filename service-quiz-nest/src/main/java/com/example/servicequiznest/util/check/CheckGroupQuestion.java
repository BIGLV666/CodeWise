package com.example.servicequiznest.util.check;

import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.dto.UpdateGroupQuestionDto;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 小组题目入参校验：新增与修改共用同一套字段上限。
 *
 * <p>平台题目（{@code questionId} 非空）只校验题目 id，其余字段按约定忽略；
 * 小组自建题目则校验必填项与长度上限，上限与 service-question 的 {@code question}
 * 表保持一致，避免同步建题/改题时才由下游报错。</p>
 *
 * <p>修改请求体里的 {@code questionId} 不参与定位：真正被修改的题目由路径上的
 * {@code groupQuestionId} 对应的小组索引决定，防止调用方改到别的小组的题目。</p>
 */
public final class CheckGroupQuestion {

    private static final int TITLE_MAX_CODE_POINTS = 200;
    private static final int TAGS_MAX_CODE_POINTS = 255;
    /** MySQL TEXT 列的最大字节数。 */
    private static final int TEXT_MAX_BYTES = 65_535;

    private CheckGroupQuestion() {
    }

    /**
     * 校验新增题目入参。
     *
     * @param dto 新增题目请求体
     * @throws BizException 入参为空、必填项缺失或字段超限时抛出
     */
    public static void checkCreate(CreateGroupQuestionDto dto) {
        if (dto == null) {
            throw new BizException(QuizNestErrorCode.QUESTION_REQUIRED);
        }
        if (dto.getGroupId() == null) {
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        if (dto.getQuestionId() != null) {
            // 平台题目只引用已有题目，title/description 等字段不参与入库，不能要求必填
            if (dto.getQuestionId() <= 0) {
                throw new BizException(CommonErrorCode.PARAM_INVALID);
            }
            return;
        }
        checkCustomFields(dto.getTitle(), dto.getDescription(), dto.getInputDesc(), dto.getOutputDesc(),
                dto.getSampleInput(), dto.getSampleOutput(), dto.getHint(), dto.getTags(),
                dto.getTimeLimit(), dto.getMemoryLimit());
    }

    /**
     * 校验修改题目入参。
     *
     * @param dto 修改题目请求体
     * @throws BizException 入参为空、必填项缺失或字段超限时抛出
     */
    public static void checkUpdate(UpdateGroupQuestionDto dto) {
        if (dto == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        checkCustomFields(dto.getTitle(), dto.getDescription(), dto.getInputDesc(), dto.getOutputDesc(),
                dto.getSampleInput(), dto.getSampleOutput(), dto.getHint(), dto.getTags(),
                dto.getTimeLimit(), dto.getMemoryLimit());
    }

    /** 新增/修改共用的自建题字段校验。 */
    private static void checkCustomFields(String title, String description, String inputDesc, String outputDesc,
                                          String sampleInput, String sampleOutput, String hint, String tags,
                                          Integer timeLimit, Integer memoryLimit) {
        if (!StringUtils.hasText(title)) {
            throw new BizException(QuizNestErrorCode.QUESTION_TITLE_REQUIRED);
        }
        if (!StringUtils.hasText(description)) {
            throw new BizException(QuizNestErrorCode.QUESTION_CONTENT_REQUIRED);
        }
        if (title.codePointCount(0, title.length()) > TITLE_MAX_CODE_POINTS) {
            throw new BizException(QuizNestErrorCode.QUESTION_TITLE_TOO_LONG, TITLE_MAX_CODE_POINTS);
        }
        if (tags != null && tags.codePointCount(0, tags.length()) > TAGS_MAX_CODE_POINTS) {
            throw new BizException(QuizNestErrorCode.QUESTION_TAGS_TOO_LONG, TAGS_MAX_CODE_POINTS);
        }
        checkTextLength(inputDesc, "输入描述");
        checkTextLength(outputDesc, "输出描述");
        checkTextLength(sampleInput, "样例输入");
        checkTextLength(sampleOutput, "样例输出");
        checkTextLength(hint, "提示");
        if (timeLimit == null || timeLimit <= 0) {
            throw new BizException(QuizNestErrorCode.QUESTION_TIME_LIMIT_INVALID);
        }
        if (memoryLimit == null || memoryLimit <= 0) {
            throw new BizException(QuizNestErrorCode.QUESTION_MEMORY_LIMIT_INVALID);
        }
    }

    /** TEXT 列按 UTF-8 字节数限制，中文/emoji 不能按 String.length() 判断。 */
    private static void checkTextLength(String value, String fieldName) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > TEXT_MAX_BYTES) {
            throw new BizException(QuizNestErrorCode.QUESTION_TEXT_TOO_LONG, fieldName);
        }
    }
}
