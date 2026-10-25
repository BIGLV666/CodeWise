package com.example.servicequiznest.util.check;

import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.dto.UpdateGroupQuestionDto;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * util/check 包的校验边界：非法入参必须在本服务内被挡下，不产生任何远程调用。
 */
class CheckGroupQuestionDtoTest {

    @Test
    @DisplayName("建组：空对象/空名称/缺 token/非法加入方式")
    void checkCreateGroupDto() {
        assertCode(CommonErrorCode.PARAM_MISSING, () -> CheckGroup.checkCreate(null));
        assertCode(QuizNestErrorCode.GROUP_NAME_REQUIRED,
                () -> CheckGroup.checkCreate(createGroup(" ", "token", "FREE")));
        assertCode(QuizNestErrorCode.CREATE_TOKEN_REQUIRED,
                () -> CheckGroup.checkCreate(createGroup("小组", null, "FREE")));
        assertCode(QuizNestErrorCode.GROUP_TYPE_REQUIRED,
                () -> CheckGroup.checkCreate(createGroup("小组", "token", "")));
        assertCode(QuizNestErrorCode.GROUP_TYPE_INVALID,
                () -> CheckGroup.checkCreate(createGroup("小组", "token", "UNKNOWN")));
    }

    @Test
    @DisplayName("改组：空对象/空 id/空名称/非法加入方式")
    void checkGroupDto() {
        assertCode(CommonErrorCode.PARAM_MISSING, () -> CheckGroup.checkUpdate(null));
        assertCode(QuizNestErrorCode.GROUP_ID_REQUIRED,
                () -> CheckGroup.checkUpdate(group(null, "小组", "FREE")));
        assertCode(QuizNestErrorCode.GROUP_NAME_REQUIRED,
                () -> CheckGroup.checkUpdate(group(1L, " ", "FREE")));
        assertCode(QuizNestErrorCode.GROUP_TYPE_INVALID,
                () -> CheckGroup.checkUpdate(group(1L, "小组", "UNKNOWN")));
    }

    @Test
    @DisplayName("新增题目：空对象 / 缺小组 id / 平台题 id 非法")
    void checkCreateQuestionRequiredFields() {
        assertCode(QuizNestErrorCode.QUESTION_REQUIRED, () -> CheckGroupQuestion.checkCreate(null));
        assertCode(QuizNestErrorCode.GROUP_ID_REQUIRED,
                () -> CheckGroupQuestion.checkCreate(validCreate().groupId(null).build()));
        assertCode(CommonErrorCode.PARAM_INVALID,
                () -> CheckGroupQuestion.checkCreate(validCreate().groupId(1L).questionId(0L).build()));
    }

    @Test
    @DisplayName("新增题目：平台题忽略标题等自建字段")
    void platformQuestionDoesNotRequireCustomFields() {
        // 不抛异常即通过：平台题只引用已有题目
        CheckGroupQuestion.checkCreate(CreateGroupQuestionDto.builder()
                .groupId(1L).questionId(7L).build());
    }

    @Test
    @DisplayName("新增题目：自建题标题/描述必填")
    void customQuestionRequiresTitleAndDescription() {
        assertCode(QuizNestErrorCode.QUESTION_TITLE_REQUIRED,
                () -> CheckGroupQuestion.checkCreate(validCreate().title(" ").build()));
        assertCode(QuizNestErrorCode.QUESTION_CONTENT_REQUIRED,
                () -> CheckGroupQuestion.checkCreate(validCreate().description(null).build()));
    }

    @Test
    @DisplayName("新增题目：标题/标签按字符数，TEXT 字段按 UTF-8 字节数限长")
    void customQuestionLengthLimits() {
        assertCode(QuizNestErrorCode.QUESTION_TITLE_TOO_LONG,
                () -> CheckGroupQuestion.checkCreate(validCreate().title("标".repeat(201)).build()));
        assertCode(QuizNestErrorCode.QUESTION_TAGS_TOO_LONG,
                () -> CheckGroupQuestion.checkCreate(validCreate().tags("t".repeat(256)).build()));
        assertCode(QuizNestErrorCode.QUESTION_TEXT_TOO_LONG,
                () -> CheckGroupQuestion.checkCreate(validCreate().hint("a".repeat(65_536)).build()));
        // 边界值 200 字符 / 255 字符 / 65535 字节允许通过
        CheckGroupQuestion.checkCreate(validCreate().title("标".repeat(200)).tags("t".repeat(255))
                .hint("a".repeat(65_535)).build());
    }

    @Test
    @DisplayName("新增题目：时间/内存限制必须为正数")
    void customQuestionLimitsRequired() {
        assertCode(QuizNestErrorCode.QUESTION_TIME_LIMIT_INVALID,
                () -> CheckGroupQuestion.checkCreate(validCreate().timeLimit(0).build()));
        assertCode(QuizNestErrorCode.QUESTION_MEMORY_LIMIT_INVALID,
                () -> CheckGroupQuestion.checkCreate(validCreate().memoryLimit(null).build()));
    }

    @Test
    @DisplayName("修改题目：空对象/必填项/长度/限制与新增保持一致")
    void checkUpdateQuestionDto() {
        assertCode(CommonErrorCode.PARAM_MISSING, () -> CheckGroupQuestion.checkUpdate(null));
        assertCode(QuizNestErrorCode.QUESTION_TITLE_REQUIRED,
                () -> CheckGroupQuestion.checkUpdate(validUpdate().title("").build()));
        assertCode(QuizNestErrorCode.QUESTION_CONTENT_REQUIRED,
                () -> CheckGroupQuestion.checkUpdate(validUpdate().description(null).build()));
        assertCode(QuizNestErrorCode.QUESTION_TITLE_TOO_LONG,
                () -> CheckGroupQuestion.checkUpdate(validUpdate().title("标".repeat(201)).build()));
        assertCode(QuizNestErrorCode.QUESTION_TAGS_TOO_LONG,
                () -> CheckGroupQuestion.checkUpdate(validUpdate().tags("t".repeat(256)).build()));
        assertCode(QuizNestErrorCode.QUESTION_TEXT_TOO_LONG,
                () -> CheckGroupQuestion.checkUpdate(validUpdate().sampleInput("中".repeat(22_000)).build()));
        assertCode(QuizNestErrorCode.QUESTION_TIME_LIMIT_INVALID,
                () -> CheckGroupQuestion.checkUpdate(validUpdate().timeLimit(-1).build()));
        assertCode(QuizNestErrorCode.QUESTION_MEMORY_LIMIT_INVALID,
                () -> CheckGroupQuestion.checkUpdate(validUpdate().memoryLimit(0).build()));
    }

    private static void assertCode(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(expected.getCode());
    }

    private static CreateGroupDto createGroup(String name, String token, String type) {
        CreateGroupDto dto = new CreateGroupDto();
        dto.setGroupName(name);
        dto.setToken(token);
        dto.setGroupType(type);
        return dto;
    }

    private static GroupDto group(Long id, String name, String type) {
        GroupDto dto = new GroupDto();
        dto.setGroupId(id);
        dto.setGroupName(name);
        dto.setGroupType(type);
        return dto;
    }

    private static CreateGroupQuestionDto.CreateGroupQuestionDtoBuilder validCreate() {
        return CreateGroupQuestionDto.builder()
                .groupId(1L)
                .title("两数之和")
                .description("给定两个整数，输出它们的和")
                .inputDesc("两个整数")
                .outputDesc("它们的和")
                .sampleInput("1 2")
                .sampleOutput("3")
                .timeLimit(1000)
                .memoryLimit(256);
    }

    private static UpdateGroupQuestionDto.UpdateGroupQuestionDtoBuilder validUpdate() {
        return UpdateGroupQuestionDto.builder()
                .questionId(7L)
                .title("两数之和")
                .description("给定两个整数，输出它们的和")
                .inputDesc("两个整数")
                .outputDesc("它们的和")
                .sampleInput("1 2")
                .sampleOutput("3")
                .timeLimit(1000)
                .memoryLimit(256);
    }
}
