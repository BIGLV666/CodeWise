package com.example.servicequiznest.service;

import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.support.AbstractGroupIntegrationTest;
import com.example.servicequiznest.support.TestContainers;
import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 小组退出 + root 晋升（晋升算法）的真库集成测试。
 *
 * <p>覆盖：指定继承人 / 管理员优先 / 普通成员兜底 / 非法指定继承人回退 / 最后一人删组 /
 * 非成员 / 待审批占位 / 普通成员 / 跨组隔离 / 中途失败整体回滚 / 重复退出。</p>
 */
class GroupLeaveGroupIntegrationTest extends AbstractGroupIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = TestContainers.newMySql();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        TestContainers.registerMySql(registry, MYSQL);
    }

    @Test
    @DisplayName("root 指定继承人：即使组里有管理员，也优先用指定的继承人")
    void rootLeave_withDesignatedHeir_prefersDesignatedOverAdmin() {
        insertGroup(1L, ROOT, 3);
        insertMember(ROOT, 1L, "ROOT", "APPROVED", "2026-01-01 00:00:00");
        insertMember(ADMIN, 1L, "ADMIN", "APPROVED", "2026-09-01 00:00:00");
        insertMember(MEMBER, 1L, "MEMBER", "APPROVED", "2026-02-01 00:00:00");
        insertMember(PENDING, 1L, "MEMBER", "PENDING", "2026-09-02 00:00:00");

        UserContext.setUserId(ROOT);
        assertThat(groupService.leaveGroup(1L, MEMBER)).isTrue();

        assertThat(ownerOf(1L)).isEqualTo(MEMBER);
        assertThat(roleOf(MEMBER, 1L)).isEqualTo("ROOT");
        assertThat(roleOf(ADMIN, 1L)).isEqualTo("ADMIN");
        assertThat(rowCount("group_id = 1 and user_id = ?", ROOT)).isZero();
        assertThat(statusOf(PENDING, 1L)).isEqualTo("PENDING");
        assertInvariants(1L);
    }

    @Test
    @DisplayName("未指定继承人：管理员优先于普通成员（即使普通成员的记录更“新”）")
    void rootLeave_withoutDesignatedHeir_prefersAdmin() {
        insertGroup(2L, ROOT, 3);
        insertMember(ROOT, 2L, "ROOT", "APPROVED", "2026-01-01 00:00:00");
        insertMember(ADMIN, 2L, "ADMIN", "APPROVED", "2026-02-01 00:00:00");
        insertMember(MEMBER, 2L, "MEMBER", "APPROVED", "2026-09-01 00:00:00");

        UserContext.setUserId(ROOT);
        assertThat(groupService.leaveGroup(2L, null)).isTrue();

        assertThat(ownerOf(2L)).isEqualTo(ADMIN);
        assertThat(roleOf(ADMIN, 2L)).isEqualTo("ROOT");
        assertThat(roleOf(MEMBER, 2L)).isEqualTo("MEMBER");
        assertInvariants(2L);
    }

    @Test
    @DisplayName("未指定继承人且没有管理员：由普通成员继承")
    void rootLeave_withoutDesignatedHeir_noAdmin_fallsBackToMember() {
        insertGroup(3L, ROOT, 2);
        insertMember(ROOT, 3L, "ROOT", "APPROVED", "2026-01-01 00:00:00");
        insertMember(MEMBER, 3L, "MEMBER", "APPROVED", "2026-01-02 00:00:00");

        UserContext.setUserId(ROOT);
        assertThat(groupService.leaveGroup(3L, null)).isTrue();

        assertThat(ownerOf(3L)).isEqualTo(MEMBER);
        assertThat(roleOf(MEMBER, 3L)).isEqualTo("ROOT");
        assertInvariants(3L);
    }

    @Test
    @DisplayName("指定的继承人不在组内：静默回退到自动晋升，不阻断 root 退出")
    void rootLeave_withInvalidDesignatedHeir_fallsBackToAutoPromotion() {
        insertGroup(4L, ROOT, 2);
        insertMember(ROOT, 4L, "ROOT", "APPROVED", "2026-01-01 00:00:00");
        insertMember(MEMBER, 4L, "MEMBER", "APPROVED", "2026-01-02 00:00:00");

        UserContext.setUserId(ROOT);
        assertThat(groupService.leaveGroup(4L, 999999L)).isTrue();

        assertThat(ownerOf(4L)).isEqualTo(MEMBER);
        assertInvariants(4L);
    }

    @Test
    @DisplayName("指定自己当继承人：回退到自动晋升")
    void rootLeave_withSelfAsDesignatedHeir_fallsBackToAutoPromotion() {
        insertGroup(5L, ROOT, 2);
        insertMember(ROOT, 5L, "ROOT", "APPROVED", "2026-01-01 00:00:00");
        insertMember(ADMIN, 5L, "ADMIN", "APPROVED", "2026-01-02 00:00:00");

        UserContext.setUserId(ROOT);
        assertThat(groupService.leaveGroup(5L, ROOT)).isTrue();

        assertThat(ownerOf(5L)).isEqualTo(ADMIN);
        assertInvariants(5L);
    }

    @Test
    @DisplayName("最后一名 APPROVED 成员退出：小组连同 PENDING/REJECTED 占位一起删除")
    void rootLeave_lastApprovedMember_purgesGroupAndPlaceholders() {
        insertGroup(6L, ROOT, 1);
        insertMember(ROOT, 6L, "ROOT", "APPROVED");
        insertMember(PENDING, 6L, "MEMBER", "PENDING");
        insertMember(MEMBER2, 6L, "MEMBER", "REJECTED");

        UserContext.setUserId(ROOT);
        assertThat(groupService.leaveGroup(6L, null)).isTrue();

        assertGroupPurged(6L);
    }

    @Test
    @DisplayName("非本组成员退出：抛 NOTFOUND_USER_IN_GROUP，且不动任何数据")
    void leaveGroup_whenNotMember_throwsNotInGroup() {
        insertGroup(7L, ROOT, 1);
        insertMember(ROOT, 7L, "ROOT", "APPROVED");

        UserContext.setUserId(MEMBER2);
        BizException ex = catchThrowableOfType(() -> groupService.leaveGroup(7L, null), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP.getCode());
        assertThat(ownerOf(7L)).isEqualTo(ROOT);
        assertThat(memberCountOf(7L)).isEqualTo(1L);
        assertInvariants(7L);
    }

    @Test
    @DisplayName("待审批申请人撤销申请：只删占位记录，不改 member_count、不动小组")
    void leaveGroup_pendingApplicant_removesPlaceholderWithoutCounting() {
        insertGroup(8L, ROOT, 1);
        insertMember(ROOT, 8L, "ROOT", "APPROVED");
        insertMember(PENDING, 8L, "MEMBER", "PENDING");

        UserContext.setUserId(PENDING);
        assertThat(groupService.leaveGroup(8L, null)).isTrue();

        assertThat(rowCount("group_id = 8 and user_id = ?", PENDING)).isZero();
        assertThat(memberCountOf(8L)).isEqualTo(1L);
        assertInvariants(8L);
    }

    @Test
    @DisplayName("普通成员退出：删记录 + 人数 -1，root 不变")
    void leaveGroup_normalMember_decrementsCount() {
        insertGroup(9L, ROOT, 3);
        insertMember(ROOT, 9L, "ROOT", "APPROVED");
        insertMember(ADMIN, 9L, "ADMIN", "APPROVED");
        insertMember(MEMBER, 9L, "MEMBER", "APPROVED");

        UserContext.setUserId(MEMBER);
        assertThat(groupService.leaveGroup(9L, null)).isTrue();

        assertThat(rowCount("group_id = 9 and user_id = ?", MEMBER)).isZero();
        assertThat(ownerOf(9L)).isEqualTo(ROOT);
        assertThat(roleOf(ROOT, 9L)).isEqualTo("ROOT");
        assertInvariants(9L);
    }

    @Test
    @DisplayName("晋升只影响本组：其它小组的 root 指针与成员角色不受影响")
    void promotion_doesNotTouchOtherGroups() {
        insertGroup(10L, ROOT, 2);
        insertMember(ROOT, 10L, "ROOT", "APPROVED");
        insertMember(MEMBER, 10L, "MEMBER", "APPROVED");
        insertGroup(11L, MEMBER, 2);
        insertMember(MEMBER, 11L, "ROOT", "APPROVED");
        insertMember(ADMIN, 11L, "ADMIN", "APPROVED");

        UserContext.setUserId(ROOT);
        assertThat(groupService.leaveGroup(10L, null)).isTrue();

        assertThat(ownerOf(10L)).isEqualTo(MEMBER);
        assertThat(ownerOf(11L)).as("其它小组的 root 指针不能被跨组改写").isEqualTo(MEMBER);
        assertThat(roleOf(MEMBER, 11L)).as("其它小组的成员角色不能被跨组改写").isEqualTo("ROOT");
        assertThat(roleOf(ADMIN, 11L)).isEqualTo("ADMIN");
        assertThat(memberCountOf(11L)).isEqualTo(2L);
        assertInvariants(10L);
        assertInvariants(11L);
    }

    @Test
    @DisplayName("晋升中途失败：整体回滚，不留“root 已换但记录没删”的半成品")
    void promotion_whenCountUpdateFails_rollsBackEverything() {
        // member_count=0 是脏数据，会让第 4 步 decMemberCount 命中 0 行
        insertGroup(12L, ROOT, 0);
        insertMember(ROOT, 12L, "ROOT", "APPROVED");
        insertMember(ADMIN, 12L, "ADMIN", "APPROVED");

        UserContext.setUserId(ROOT);
        BizException ex = catchThrowableOfType(() -> groupService.leaveGroup(12L, ADMIN), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(QuizNestErrorCode.LEAVE_GROUP_FAILED.getCode());
        assertThat(ownerOf(12L)).as("CAS 换过的 root 指针必须回滚").isEqualTo(ROOT);
        assertThat(roleOf(ADMIN, 12L)).as("已升为 ROOT 的继承人记录必须回滚").isEqualTo("ADMIN");
        assertThat(roleOf(ROOT, 12L)).as("退出者的 ROOT 记录必须回滚").isEqualTo("ROOT");
    }

    @Test
    @DisplayName("root 连点两次：第二次失败且不会产生第二个 ROOT")
    void leaveGroup_twice_rejectedWithoutSecondPromotion() {
        insertGroup(13L, ROOT, 2);
        insertMember(ROOT, 13L, "ROOT", "APPROVED");
        insertMember(ADMIN, 13L, "ADMIN", "APPROVED");

        UserContext.setUserId(ROOT);
        assertThat(groupService.leaveGroup(13L, null)).isTrue();

        BizException ex = catchThrowableOfType(() -> groupService.leaveGroup(13L, null), BizException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP.getCode());
        assertInvariants(13L);
    }

    @Test
    @DisplayName("小组ID为空：抛 GROUP_ID_REQUIRED")
    void leaveGroup_withNullGroupId_throwsGroupIdRequired() {
        UserContext.setUserId(ROOT);
        BizException ex = catchThrowableOfType(() -> groupService.leaveGroup(null, null), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(QuizNestErrorCode.GROUP_ID_REQUIRED.getCode());
    }
}