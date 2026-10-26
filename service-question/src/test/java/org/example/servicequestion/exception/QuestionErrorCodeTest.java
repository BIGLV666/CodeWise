package org.example.servicequestion.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码护栏：编码必须符合 {@code 类别*10000 + 服务号*100 + 序号} 且本枚举内唯一。
 *
 * <p>客户端按 code 分支，重复码或错位码会把「非组员」「题目不在小组」等语义串错，
 * 因此用反射遍历枚举做回归。</p>
 */
class QuestionErrorCodeTest {

    private static final int SERVICE_NUMBER = 2;
    private static final Set<Integer> CATEGORIES = Set.of(400, 401, 403, 404, 409, 429, 500);

    @Test
    @DisplayName("错误码唯一：同一枚举内不允许重复 code")
    void codesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (QuestionErrorCode errorCode : QuestionErrorCode.values()) {
            assertThat(seen.add(errorCode.getCode()))
                    .as("错误码 %s(%d) 重复", errorCode.name(), errorCode.getCode())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("错误码公式：类别合法、服务号=02、序号 1-99")
    void codesFollowFormula() {
        for (QuestionErrorCode errorCode : QuestionErrorCode.values()) {
            int code = errorCode.getCode();
            int category = code / 10_000;
            int serviceNumber = (code / 100) % 100;
            int sequence = code % 100;

            assertThat(CATEGORIES).as("%s 的类别 %d", errorCode.name(), category).contains(category);
            assertThat(serviceNumber).as("%s 的服务号", errorCode.name()).isEqualTo(SERVICE_NUMBER);
            assertThat(sequence).as("%s 的序号", errorCode.name()).isBetween(1, 99);
            assertThat(code).as("%s 的编码应等于公式值", errorCode.name())
                    .isEqualTo(category * 10_000 + SERVICE_NUMBER * 100 + sequence);
        }
    }

    @Test
    @DisplayName("消息面向用户：非空且不含 SQL/异常类名")
    void messagesAreUserFacing() {
        for (QuestionErrorCode errorCode : QuestionErrorCode.values()) {
            assertThat(errorCode.getMessage()).as("%s 的文案", errorCode.name()).isNotBlank();
            assertThat(errorCode.getMessage().toLowerCase())
                    .as("%s 的文案不应包含技术细节", errorCode.name())
                    .doesNotContain("select ", "insert ", "update ", "exception", "sql");
        }
    }

    @Test
    @DisplayName("小组提交错误码：参数、权限、资源与保存失败分段正确")
    void groupSubmitCodesUseExpectedCategories() {
        assertThat(QuestionErrorCode.GROUP_ID_REQUIRED.getCode()).isEqualTo(4000238);
        assertThat(QuestionErrorCode.GROUP_ID_INVALID.getCode()).isEqualTo(4000239);
        assertThat(QuestionErrorCode.GROUP_SUBMIT_SCENE_REQUIRED.getCode()).isEqualTo(4000240);
        assertThat(QuestionErrorCode.GROUP_MEMBER_REQUIRED.getCode()).isEqualTo(4030204);
        assertThat(QuestionErrorCode.GROUP_SUBMIT_RECORD_VIEW_FORBIDDEN.getCode()).isEqualTo(4030205);
        assertThat(QuestionErrorCode.GROUP_QUESTION_NOT_IN_GROUP.getCode()).isEqualTo(4040209);
        assertThat(QuestionErrorCode.SUBMIT_RECORD_SAVE_FAILED.getCode()).isEqualTo(5000219);
        assertThat(QuestionErrorCode.GROUP_SUBMIT_RECORD_SAVE_FAILED.getCode()).isEqualTo(5000220);
    }
}
