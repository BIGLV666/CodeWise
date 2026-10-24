package com.example.servicequiznest.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码护栏：编码必须符合 {@code 类别*10000 + 服务号*100 + 序号} 且全局唯一。
 *
 * <p>曾经出现过 {@code 401103}（少一位）和两处重复码（4041102/5001105），
 * 客户端按 code 分支时会串错语义，因此用反射遍历枚举做回归。</p>
 */
class QuizNestErrorCodeTest {

    private static final int SERVICE_NUMBER = 11;
    private static final Set<Integer> CATEGORIES = Set.of(400, 401, 403, 404, 409, 429, 500);

    @Test
    @DisplayName("错误码唯一：同一枚举内不允许重复 code")
    void codesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (QuizNestErrorCode errorCode : QuizNestErrorCode.values()) {
            assertThat(seen.add(errorCode.getCode()))
                    .as("错误码 %s(%d) 重复", errorCode.name(), errorCode.getCode())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("错误码公式：类别合法、服务号=11、序号 1-99")
    void codesFollowFormula() {
        for (QuizNestErrorCode errorCode : QuizNestErrorCode.values()) {
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
        for (QuizNestErrorCode errorCode : QuizNestErrorCode.values()) {
            assertThat(errorCode.getMessage()).as("%s 的文案", errorCode.name()).isNotBlank();
            assertThat(errorCode.getMessage().toLowerCase())
                    .as("%s 的文案不应包含技术细节", errorCode.name())
                    .doesNotContain("select ", "insert ", "update ", "exception", "sql");
        }
    }
}
