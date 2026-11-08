package com.example.servicequiznest.service;

import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.support.AbstractGroupIntegrationTest;
import com.example.servicequiznest.support.TestContainers;
import com.example.servicequiznest.vo.GroupMembershipVo;
import com.example.servicequiznest.vo.MemberVO;
import com.example.servicequiznest.vo.UserGroup;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.quizNest.ApproveResult;
import org.example.serviceapi.dto.quizNest.BroadCast;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 小组成员管理链路集成测试：真 MySQL 8.4（业务表 + outboxpro_outbox）。
 *
 * <p>覆盖：审批通过/拒绝落库、member_count 维护、拒绝后重新申请、权限校验、
 * 成员列表用户信息映射、待审批/已拒绝过滤、我的小组列表、广播接收人过滤，
 * 以及并发审批与批量广播压测。</p>
 */
class GroupMemberManagementIntegrationTest extends AbstractGroupIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = TestContainers.newMySql();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        TestContainers.registerMySql(registry, MYSQL);
    }

    @MockBean
    private UserFeignClient userFeignClient;

    @Autowired
    private GroupBroadcastService groupBroadcastService;

    @BeforeEach
    void cleanOutbox() {
        jdbcTemplate.update("delete from outboxpro_outbox");
    }

    // ---------- 审批 ----------

    @Test
    @DisplayName("审批通过：申请人记录转 APPROVED、member_count+1、Outbox 事件接收人是申请人")
    void approveMember_updatesApplicantAndCount() {
        long groupId = 601L;
        insertGroup(groupId, ROOT, 1);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");

        UserContext.setUserId(ROOT);
        groupService.approveMember(memberId(MEMBER2, groupId), "欢迎加入", true);

        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertInvariants(groupId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select event_type, exchange_name, routing_key from outboxpro_outbox");
        assertThat(row.get("event_type")).isEqualTo(EventTypes.GROUP_APPROVAL_RESULT);
        assertThat(row.get("exchange_name")).isEqualTo("notification.exchange");
        assertThat(row.get("routing_key")).isEqualTo("notification.approve.result.routing");

        ApproveResult event = EnvelopeCodec.unwrap(jdbcTemplate.queryForObject(
                "select payload_json from outboxpro_outbox", String.class), ApproveResult.class);
        assertThat(event.getToUserId()).as("事件接收人必须是申请人，而不是审批人").isEqualTo(MEMBER2);
        assertThat(event.getGroupId()).isEqualTo(groupId);
        assertThat(event.getContext()).isEqualTo("欢迎加入");
        assertThat(event.getMessageId()).startsWith("quiz:approve:").hasSizeLessThanOrEqualTo(64);
        assertThat(event.getSendTime()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("审批拒绝：状态写 REJECTED（不是 JECTED），不增加人数，且可重新申请")
    void rejectMember_writesRejectedAndAllowsReapply() {
        long groupId = 602L;
        insertGroup(groupId, ROOT, 1);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");

        UserContext.setUserId(ROOT);
        groupService.approveMember(memberId(MEMBER2, groupId), null, false);

        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("REJECTED");
        assertThat(memberCountOf(groupId)).isEqualTo(1L);
        assertInvariants(groupId);

        UserContext.setUserId(MEMBER2);
        groupService.joinGroup(groupId, null);
        assertThat(statusOf(MEMBER2, groupId)).as("REJECTED 必须能改回 PENDING").isEqualTo("PENDING");
        assertThat(memberCountOf(groupId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("审批权限：普通成员不能审批，申请记录保持不变")
    void approveMember_memberHasNoPermission() {
        long groupId = 603L;
        insertGroup(groupId, ROOT, 2);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");

        UserContext.setUserId(MEMBER);
        BizException exception = catchThrowableOfType(
                () -> groupService.approveMember(memberId(MEMBER2, groupId), null, true),
                BizException.class);

        assertThat(exception.getCode()).isEqualTo(QuizNestErrorCode.NO_PERMISSION_APPROVE.getCode());
        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("select count(*) from outboxpro_outbox", Long.class)).isZero();
    }

    @Test
    @DisplayName("重复审批：第二次命中冲突码，人数不重复累加")
    void approveMember_twice_countsOnce() {
        long groupId = 604L;
        insertGroup(groupId, ROOT, 1);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");
        long applicantMemberId = memberId(MEMBER2, groupId);

        UserContext.setUserId(ROOT);
        groupService.approveMember(applicantMemberId, null, true);

        BizException exception = catchThrowableOfType(
                () -> groupService.approveMember(applicantMemberId, null, true), BizException.class);

        assertThat(exception.getCode()).isEqualTo(QuizNestErrorCode.APPROVE_HAVE_CLASH.getCode());
        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from outboxpro_outbox", Long.class))
                .as("重复审批不能重复发通知").isEqualTo(1);
    }

    // ---------- 成员查询 ----------

    @Test
    @DisplayName("成员列表：每个成员的昵称/头像按自己的 userId 映射，不能全部显示调用者")
    void groupMembers_mapsEachMemberToOwnUser() {
        long groupId = 605L;
        insertGroup(groupId, ROOT, 3);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(ADMIN, groupId, "ADMIN", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
        stubUserList(usersOf(ROOT, ADMIN, MEMBER));

        UserContext.setUserId(ROOT);
        List<MemberVO> members = groupService.groupMembers(groupId);

        assertThat(members).hasSize(3);
        Map<Long, MemberVO> byUserId = members.stream()
                .collect(Collectors.toMap(MemberVO::userId, Function.identity()));
        for (long userId : List.of(ROOT, ADMIN, MEMBER)) {
            assertThat(byUserId.get(userId).nickName()).isEqualTo("昵称" + userId);
            assertThat(byUserId.get(userId).avatarUrl()).isEqualTo("avatar" + userId);
        }
    }

    @Test
    @DisplayName("待审批/已拒绝列表：按 name() 过滤，普通成员无权查看")
    void approvedUsers_filtersByStatusAndChecksPermission() {
        long groupId = 606L;
        insertGroup(groupId, ROOT, 3);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(ADMIN, groupId, "ADMIN", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");
        insertMember(PENDING, groupId, "MEMBER", "REJECTED");
        stubUserList(usersOf(ROOT, ADMIN, MEMBER, MEMBER2, PENDING));

        UserContext.setUserId(ROOT);
        assertThat(groupService.approvedUsers(groupId, 0))
                .extracting(MemberVO::userId).containsExactly(MEMBER2);
        assertThat(groupService.approvedUsers(groupId, 2))
                .extracting(MemberVO::userId).containsExactly(PENDING);
        assertThat(groupService.approvedUsers(groupId, 1))
                .extracting(MemberVO::userId).containsExactlyInAnyOrder(ROOT, ADMIN, MEMBER);

        BizException invalid = catchThrowableOfType(
                () -> groupService.approvedUsers(groupId, 9), BizException.class);
        assertThat(invalid.getCode()).isEqualTo(QuizNestErrorCode.INVALID_JOIN_STATUS.getCode());

        UserContext.setUserId(MEMBER);
        BizException denied = catchThrowableOfType(
                () -> groupService.approvedUsers(groupId, 0), BizException.class);
        assertThat(denied.getCode()).isEqualTo(QuizNestErrorCode.NO_PERMISSION_APPROVE.getCode());
    }

    @Test
    @DisplayName("我的小组列表：只返回 APPROVED 小组，并映射 role/member_count/join_time")
    void getAllUserGroup_returnsOnlyApprovedGroups() {
        insertGroup(607L, ROOT, 2);
        insertMember(ROOT, 607L, "ROOT", "APPROVED");
        insertMember(MEMBER2, 607L, "MEMBER", "APPROVED");
        jdbcTemplate.update("update group_member set join_time = '2026-01-02 03:04:05' "
                + "where group_id = 607 and user_id = ?", MEMBER2);

        insertGroup(608L, ROOT, 1);
        insertMember(ROOT, 608L, "ROOT", "APPROVED");
        insertMember(MEMBER2, 608L, "MEMBER", "PENDING");

        UserContext.setUserId(MEMBER2);
        List<UserGroup> groups = groupService.getAllUserGroup();

        assertThat(groups).hasSize(1);
        UserGroup group = groups.get(0);
        assertThat(group.groupId()).isEqualTo(607L);
        assertThat(group.groupName()).isEqualTo("g607");
        assertThat(group.role()).isEqualTo(com.example.servicequiznest.enums.GroupMemberRole.MEMBER);
        assertThat(group.memberCount()).isEqualTo(2L);
        assertThat(group.joinTime()).isNotNull();
    }

    // ---------- 广播 ----------

    @Test
    @DisplayName("广播：只发给 APPROVED 成员且排除发送人，事件时间字段为字符串")
    void broadcast_sendsOnlyToApprovedMembers() {
        long groupId = 609L;
        insertGroup(groupId, ROOT, 3);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(ADMIN, groupId, "ADMIN", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");
        insertMember(PENDING, groupId, "MEMBER", "REJECTED");
        stubUserInfo(ROOT, "组长");

        UserContext.setUserId(ROOT);
        groupBroadcastService.sendGroupBroadCastService("今晚八点刷题", groupId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select event_type, exchange_name, routing_key from outboxpro_outbox");
        assertThat(row.get("event_type")).isEqualTo(EventTypes.GROUP_BROADCAST);
        assertThat(row.get("routing_key")).isEqualTo("notification.broadcast.routing");

        BroadCast event = EnvelopeCodec.unwrap(jdbcTemplate.queryForObject(
                "select payload_json from outboxpro_outbox", String.class), BroadCast.class);
        assertThat(event.getUserIds())
                .as("PENDING/REJECTED 与发送人都不在接收人里")
                .containsExactlyInAnyOrder(ADMIN, MEMBER);
        assertThat(event.getMessageId()).hasSize(36);
        assertThat(event.getSendTime()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(event.getCreateUserName()).isEqualTo("组长");
    }

    @Test
    @DisplayName("广播权限：普通成员不能发送，不产生事件")
    void broadcast_memberHasNoPermission() {
        long groupId = 610L;
        insertGroup(groupId, ROOT, 2);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");

        UserContext.setUserId(MEMBER);
        BizException exception = catchThrowableOfType(
                () -> groupBroadcastService.sendGroupBroadCastService("hi", groupId), BizException.class);

        assertThat(exception.getCode()).isEqualTo(QuizNestErrorCode.NO_PERMISSION_SEND_BROADCAST.getCode());
        assertThat(jdbcTemplate.queryForObject("select count(*) from outboxpro_outbox", Long.class)).isZero();
    }

    // ---------- 加入结果 / 成员身份 ----------

    @Test
    @DisplayName("审批制加入：返回 PENDING，成员身份接口可查到待审批状态")
    void approvalJoin_returnsPendingMembership() {
        long groupId = 613L;
        insertGroup(groupId, ROOT, 1);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");

        UserContext.setUserId(MEMBER2);
        GroupMembershipVo joined = groupService.joinGroup(groupId, null);

        assertThat(joined.joinStatus()).isEqualTo(GroupJoinStatus.PENDING);
        assertThat(joined.role()).isEqualTo(com.example.servicequiznest.enums.GroupMemberRole.MEMBER);
        assertThat(joined.groupMemberId()).isNotNull();
        assertThat(joined.joinTime()).isNull();
        assertThat(groupService.getMembership(groupId).joinStatus())
                .isEqualTo(GroupJoinStatus.PENDING);
        assertThat(memberCountOf(groupId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("自由加入：返回 APPROVED；非成员身份查询返回 null")
    void freeJoin_returnsApprovedAndNonMemberIsNull() {
        long groupId = 614L;
        insertGroup(groupId, ROOT, 1);
        jdbcTemplate.update("update `group` set group_type = 'FREE' where group_id = ?", groupId);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");

        UserContext.setUserId(MEMBER2);
        assertThat(groupService.getMembership(groupId)).isNull();
        GroupMembershipVo joined = groupService.joinGroup(groupId, null);

        assertThat(joined.joinStatus()).isEqualTo(GroupJoinStatus.APPROVED);
        assertThat(joined.joinTime()).isNotNull();
        assertThat(groupService.getMembership(groupId).joinStatus())
                .isEqualTo(GroupJoinStatus.APPROVED);
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertInvariants(groupId);
    }

    // ---------- 设置管理员 / 踢人 ----------

    @Test
    @DisplayName("设置管理员：ROOT 可升可降，普通成员角色只改角色不动人数")
    void updateMemberRole_rootPromotesAndDemotes() {
        long groupId = 615L;
        insertGroup(groupId, ROOT, 2);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");

        UserContext.setUserId(ROOT);
        groupService.updateMemberRole(groupId, MEMBER, com.example.servicequiznest.enums.GroupMemberRole.ADMIN);
        assertThat(roleOf(MEMBER, groupId)).isEqualTo("ADMIN");

        groupService.updateMemberRole(groupId, MEMBER, com.example.servicequiznest.enums.GroupMemberRole.MEMBER);
        assertThat(roleOf(MEMBER, groupId)).isEqualTo("MEMBER");
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertInvariants(groupId);
    }

    @Test
    @DisplayName("设置管理员：仅 ROOT 可操作，不能改 ROOT，不能设置 ROOT 角色，目标必须已加入")
    void updateMemberRole_permissionAndTargetChecks() {
        long groupId = 616L;
        insertGroup(groupId, ROOT, 3);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(ADMIN, groupId, "ADMIN", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");

        UserContext.setUserId(ADMIN);
        BizException notRoot = catchThrowableOfType(
                () -> groupService.updateMemberRole(groupId, MEMBER,
                        com.example.servicequiznest.enums.GroupMemberRole.ADMIN), BizException.class);
        assertThat(notRoot.getCode()).isEqualTo(QuizNestErrorCode.NO_PERMISSION_SET_ROLE.getCode());

        UserContext.setUserId(ROOT);
        BizException targetRoot = catchThrowableOfType(
                () -> groupService.updateMemberRole(groupId, ROOT,
                        com.example.servicequiznest.enums.GroupMemberRole.ADMIN), BizException.class);
        assertThat(targetRoot.getCode()).isEqualTo(QuizNestErrorCode.CANNOT_CHANGE_ROOT_ROLE.getCode());

        BizException rootRole = catchThrowableOfType(
                () -> groupService.updateMemberRole(groupId, MEMBER,
                        com.example.servicequiznest.enums.GroupMemberRole.ROOT), BizException.class);
        assertThat(rootRole.getCode()).isEqualTo(QuizNestErrorCode.INVALID_MEMBER_ROLE.getCode());

        BizException pending = catchThrowableOfType(
                () -> groupService.updateMemberRole(groupId, MEMBER2,
                        com.example.servicequiznest.enums.GroupMemberRole.ADMIN), BizException.class);
        assertThat(pending.getCode()).isEqualTo(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP.getCode());
        assertThat(roleOf(MEMBER, groupId)).isEqualTo("MEMBER");
        assertInvariants(groupId);
    }

    @Test
    @DisplayName("踢人：ROOT 移除成员并扣人数，成员记录删除")
    void kickMember_rootRemovesAndDecrements() {
        long groupId = 617L;
        insertGroup(groupId, ROOT, 2);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");

        UserContext.setUserId(ROOT);
        groupService.kickMember(groupId, MEMBER);

        assertThat(rowCount("group_id = ? and user_id = ?", groupId, MEMBER)).isZero();
        assertThat(memberCountOf(groupId)).isEqualTo(1L);
        assertInvariants(groupId);
    }

    @Test
    @DisplayName("踢人规则：ADMIN 只能踢普通成员；不能踢 ROOT/自己；普通成员无权限")
    void kickMember_permissionMatrix() {
        long groupId = 618L;
        insertGroup(groupId, ROOT, 5);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(ADMIN, groupId, "ADMIN", "APPROVED");
        insertMember(PENDING, groupId, "ADMIN", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "APPROVED");

        UserContext.setUserId(ADMIN);
        groupService.kickMember(groupId, MEMBER);
        assertThat(rowCount("group_id = ? and user_id = ?", groupId, MEMBER)).isZero();

        BizException peerAdmin = catchThrowableOfType(
                () -> groupService.kickMember(groupId, PENDING), BizException.class);
        assertThat(peerAdmin.getCode()).isEqualTo(QuizNestErrorCode.NO_PERMISSION_KICK_MEMBER.getCode());

        BizException root = catchThrowableOfType(
                () -> groupService.kickMember(groupId, ROOT), BizException.class);
        assertThat(root.getCode()).isEqualTo(QuizNestErrorCode.CANNOT_KICK_ROOT.getCode());

        BizException self = catchThrowableOfType(
                () -> groupService.kickMember(groupId, ADMIN), BizException.class);
        assertThat(self.getCode()).isEqualTo(QuizNestErrorCode.CANNOT_KICK_SELF.getCode());

        UserContext.setUserId(ROOT);
        groupService.kickMember(groupId, PENDING);
        assertThat(rowCount("group_id = ? and user_id = ?", groupId, PENDING)).isZero();

        UserContext.setUserId(MEMBER2);
        BizException memberNoPermission = catchThrowableOfType(
                () -> groupService.kickMember(groupId, ADMIN), BizException.class);
        assertThat(memberNoPermission.getCode())
                .isEqualTo(QuizNestErrorCode.NO_PERMISSION_KICK_MEMBER.getCode());

        assertThat(memberCountOf(groupId)).isEqualTo(3L);
        assertInvariants(groupId);
    }

    // ---------- 压测 ----------

    @Test
    @DisplayName("压测：50 线程并发审批同一申请，只成功 1 次且人数只加一次")
    void concurrentApprove_exactlyOneWinner() throws InterruptedException {
        long groupId = 611L;
        insertGroup(groupId, ROOT, 1);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");
        long applicantMemberId = memberId(MEMBER2, groupId);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                UserContext.setUserId(ROOT);
                groupService.approveMember(applicantMemberId, null, true);
            });
        }
        LoadResult result = runConcurrently(tasks, 32);

        assertThat(result.success()).as("同一申请并发审批只能成功 1 次").isEqualTo(1);
        assertThat(result.failures()).allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));
        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("select count(*) from outboxpro_outbox", Long.class))
                .isEqualTo(1);
        assertInvariants(groupId);
        System.out.printf("[load] 并发审批 %d 次，成功 %d 次，失败 %d 次%n",
                result.total(), result.success(), result.failures().size());
    }

    @Test
    @DisplayName("压测：50 线程并发踢同一成员，只成功 1 次且人数不重复扣减")
    void concurrentKick_exactlyOneWinner() throws InterruptedException {
        long groupId = 619L;
        insertGroup(groupId, ROOT, 2);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                UserContext.setUserId(ROOT);
                groupService.kickMember(groupId, MEMBER);
            });
        }
        LoadResult result = runConcurrently(tasks, 32);

        assertThat(result.success()).as("同一成员并发踢出只能成功 1 次").isEqualTo(1);
        assertThat(result.failures()).allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));
        assertThat(memberCountOf(groupId)).isEqualTo(1L);
        assertInvariants(groupId);
        System.out.printf("[load] 并发踢人 %d 次，成功 %d 次，失败 %d 次%n",
                result.total(), result.success(), result.failures().size());
    }

    @Test
    @DisplayName("压测：200 名成员的小组广播只产生 1 条事件、接收人列表完整")
    void broadcast_twoHundredMembers_oneEvent() {
        long groupId = 612L;
        int memberTotal = 200;
        insertGroup(groupId, ROOT, memberTotal + 1);
        insertMember(ROOT, groupId, "ROOT", "APPROVED");
        for (int i = 0; i < memberTotal; i++) {
            insertMember(2000L + i, groupId, "MEMBER", "APPROVED");
        }
        stubUserInfo(ROOT, "组长");

        UserContext.setUserId(ROOT);
        long start = System.nanoTime();
        groupBroadcastService.sendGroupBroadCastService("压测广播", groupId);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        BroadCast event = EnvelopeCodec.unwrap(jdbcTemplate.queryForObject(
                "select payload_json from outboxpro_outbox", String.class), BroadCast.class);
        assertThat(event.getUserIds()).hasSize(memberTotal);
        assertThat(jdbcTemplate.queryForObject("select count(*) from outboxpro_outbox", Long.class))
                .isEqualTo(1);
        System.out.printf("[load] 200 人广播生产耗时 %dms%n", elapsedMs);
    }

    // ---------- 工具 ----------

    private long memberId(long userId, long groupId) {
        Long id = jdbcTemplate.queryForObject(
                "select group_member_id from group_member where group_id = ? and user_id = ?",
                Long.class, groupId, userId);
        assertThat(id).isNotNull();
        return id;
    }

    private Map<Long, UserDto> usersOf(long... userIds) {
        Map<Long, UserDto> users = new LinkedHashMap<>();
        for (long userId : userIds) {
            UserDto user = new UserDto();
            user.setUserId(String.valueOf(userId));
            user.setNickName("昵称" + userId);
            user.setAvatarUrl("avatar" + userId);
            users.put(userId, user);
        }
        return users;
    }

    private void stubUserList(Map<Long, UserDto> users) {
        when(userFeignClient.getUserList(anyList())).thenReturn(Result.success(users));
    }

    private void stubUserInfo(long userId, String nickName) {
        UserDto user = new UserDto();
        user.setUserId(String.valueOf(userId));
        user.setNickName(nickName);
        when(userFeignClient.getUserInfo(anyLong())).thenReturn(Result.success(user));
    }

    private LoadResult runConcurrently(List<Runnable> tasks, int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (Runnable task : tasks) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                    success.incrementAndGet();
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        return new LoadResult(tasks.size(), success.get(), failures);
    }

    private record LoadResult(int total, int success, List<Throwable> failures) {
    }
}
