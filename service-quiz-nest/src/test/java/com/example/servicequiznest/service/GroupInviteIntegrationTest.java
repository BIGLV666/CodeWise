package com.example.servicequiznest.service;

import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.support.AbstractGroupIntegrationTest;
import com.example.servicequiznest.support.TestContainers;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.quizNest.JoinToken;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 小组邀请码链路集成测试：真 MySQL 8.4（业务表 + outboxpro_outbox）+ 真 Redis 7。
 *
 * <p>覆盖：6 位邀请码与 7 天 TTL、Outbox 事件字段与载荷 wire 格式、邀请权限、
 * 一次性消费、重新发送覆盖旧码、PENDING/REJECTED 就地转 APPROVED、
 * 发送 3 秒去重，以及「并发加入/并发发送」压测下的计数与幂等不变量。</p>
 */
class GroupInviteIntegrationTest extends AbstractGroupIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = TestContainers.newMySql();

    @Container
    static final GenericContainer<?> REDIS = TestContainers.newRedis();

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        TestContainers.registerMySql(registry, MYSQL);
        TestContainers.registerRedis(registry, REDIS);
    }

    @MockBean
    private UserFeignClient userFeignClient;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void cleanInviteState() {
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        jdbcTemplate.update("delete from outboxpro_outbox");
    }

    // ---------- 邀请码发送 ----------

    @Test
    @DisplayName("发邀请：6 位码写入 Redis（7 天 TTL），并落一条 notification.exchange 的 Outbox 事件")
    void sendInvite_writesTokenAndOutboxEvent() {
        long groupId = createInviteGroup();
        stubInviterName("小明");
        UserContext.setUserId(ROOT);

        groupService.sendJoinTokenById(groupId, MEMBER2, "一起刷题");

        String token = currentToken(groupId, MEMBER2);
        assertThat(token).as("邀请码应为 6 位数字").matches("\\d{6}");
        assertThat(redisTemplate.getExpire(tokenKey(groupId, MEMBER2), TimeUnit.SECONDS))
                .as("邀请码 TTL 应为 7 天")
                .isBetween(RedisContext.JOIN_GROUP_TOKEN_TTL_SECONDS - 5,
                        RedisContext.JOIN_GROUP_TOKEN_TTL_SECONDS);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select event_type, exchange_name, routing_key, status from outboxpro_outbox");
        assertThat(row.get("event_type")).isEqualTo(EventTypes.GROUP_JOIN_TOKEN);
        assertThat(row.get("exchange_name")).isEqualTo("notification.exchange");
        assertThat(row.get("routing_key")).isEqualTo("notification.join.token.routing");
        assertThat(row.get("status")).isEqualTo("PENDING");

        String payload = jdbcTemplate.queryForObject(
                "select payload_json from outboxpro_outbox", String.class);
        JoinToken event = EnvelopeCodec.unwrap(payload, JoinToken.class);
        assertThat(event.getToken()).isEqualTo(token);
        assertThat(event.getGroupId()).isEqualTo(groupId);
        assertThat(event.getToUserId()).isEqualTo(MEMBER2);
        assertThat(event.getGroupName()).isEqualTo("算法小组");
        assertThat(event.getUserName()).isEqualTo("小明");
        assertThat(event.getContext()).isEqualTo("一起刷题");
        assertThat(event.getMessageId())
                .isEqualTo("quiz:join-token:" + groupId + ":" + MEMBER2 + ":" + token);
        assertThat(event.getSendTime()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("发邀请：3 秒内重复发送只落一封邀请信，码不变")
    void sendInvite_withinLockWindow_deduped() {
        long groupId = createInviteGroup();
        stubInviterName("小明");
        UserContext.setUserId(ROOT);

        groupService.sendJoinTokenById(groupId, MEMBER2, null);
        String first = currentToken(groupId, MEMBER2);
        groupService.sendJoinTokenById(groupId, MEMBER2, null);
        String second = currentToken(groupId, MEMBER2);

        assertThat(second).isEqualTo(first);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("发邀请：锁过期后重发会生成新码，旧码立即失效（旧码被覆盖）")
    void sendInvite_afterLockExpiry_overwritesOldToken() {
        long groupId = createInviteGroup();
        stubInviterName("小明");
        UserContext.setUserId(ROOT);

        groupService.sendJoinTokenById(groupId, MEMBER2, null);
        String first = currentToken(groupId, MEMBER2);
        redisTemplate.delete(sendLockKey(groupId, MEMBER2));

        String second = first;
        for (int i = 0; i < 5 && second.equals(first); i++) {
            groupService.sendJoinTokenById(groupId, MEMBER2, null);
            second = currentToken(groupId, MEMBER2);
            redisTemplate.delete(sendLockKey(groupId, MEMBER2));
        }
        assertThat(second).as("重发应生成新邀请码").isNotEqualTo(first);
        assertThat(outboxCount()).isEqualTo(2);

        UserContext.setUserId(MEMBER2);
        BizException stale = catchThrowableOfType(
                () -> groupService.joinGroup(groupId, first), BizException.class);
        assertThat(stale.getCode()).isEqualTo(QuizNestErrorCode.NOT_GROUP_JOIN_TOKEN.getCode());
        assertThat(memberCountOf(groupId)).isEqualTo(1L);

        groupService.joinGroup(groupId, second);
        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertInvariants(groupId);
    }

    @Test
    @DisplayName("发邀请：只有 ROOT/ADMIN 能邀请，普通成员、非成员、自己、已是成员都被拒绝")
    void sendInvite_permissionAndStateChecks() {
        long groupId = createInviteGroup();
        insertMember(MEMBER, groupId, "MEMBER", "APPROVED");
        jdbcTemplate.update("update `group` set member_count = 2 where group_id = ?", groupId);

        UserContext.setUserId(MEMBER);
        BizException member = catchThrowableOfType(
                () -> groupService.sendJoinTokenById(groupId, MEMBER2, null), BizException.class);
        assertThat(member.getCode()).isEqualTo(QuizNestErrorCode.NO_PERMISSION_SEND_JOIN_TOKEN.getCode());

        UserContext.setUserId(MEMBER2);
        BizException outsider = catchThrowableOfType(
                () -> groupService.sendJoinTokenById(groupId, MEMBER, null), BizException.class);
        assertThat(outsider.getCode()).isEqualTo(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP.getCode());

        UserContext.setUserId(ROOT);
        BizException self = catchThrowableOfType(
                () -> groupService.sendJoinTokenById(groupId, ROOT, null), BizException.class);
        assertThat(self.getCode()).isEqualTo(QuizNestErrorCode.CANNOT_INVITE_SELF.getCode());

        insertMember(MEMBER2, groupId, "MEMBER", "APPROVED");
        jdbcTemplate.update("update `group` set member_count = 3 where group_id = ?", groupId);
        BizException already = catchThrowableOfType(
                () -> groupService.sendJoinTokenById(groupId, MEMBER2, null), BizException.class);
        assertThat(already.getCode()).isEqualTo(QuizNestErrorCode.INVITEE_ALREADY_MEMBER.getCode());

        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("发邀请：非邀请制小组、留言超长分别拒绝")
    void sendInvite_rejectsNonInviteGroupAndOversizedContext() {
        UserContext.setUserId(ROOT);
        CreateGroupDto freeGroup = createGroupDto("自由小组", "FREE");
        long freeGroupId = groupService.cerateGroup(freeGroup).getGroupId();

        BizException notInvite = catchThrowableOfType(
                () -> groupService.sendJoinTokenById(freeGroupId, MEMBER2, null), BizException.class);
        assertThat(notInvite.getCode()).isEqualTo(QuizNestErrorCode.GROUP_NOT_INVITE.getCode());

        long inviteGroupId = createInviteGroup();
        BizException tooLong = catchThrowableOfType(
                () -> groupService.sendJoinTokenById(inviteGroupId, MEMBER2, "x".repeat(201)), BizException.class);
        assertThat(tooLong.getCode()).isEqualTo(QuizNestErrorCode.INVITE_CONTEXT_TOO_LONG.getCode());

        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("发邀请：用户服务不可用时降级为通用邀请人称呼，邀请仍成功")
    void sendInvite_userServiceDown_fallsBackToGenericName() {
        long groupId = createInviteGroup();
        when(userFeignClient.getUserInfo(anyLong())).thenThrow(new RuntimeException("user service down"));
        UserContext.setUserId(ROOT);

        groupService.sendJoinTokenById(groupId, MEMBER2, null);

        assertThat(currentToken(groupId, MEMBER2)).matches("\\d{6}");
        String payload = jdbcTemplate.queryForObject(
                "select payload_json from outboxpro_outbox", String.class);
        assertThat(EnvelopeCodec.unwrap(payload, JoinToken.class).getUserName()).isEqualTo("小组管理员");
    }

    // ---------- 凭邀请码加入 ----------

    @Test
    @DisplayName("凭邀请码加入：一次成功，码被消费；再次使用同一码失败")
    void joinWithInviteToken_succeedsAndConsumesToken() {
        long groupId = createInviteGroup();
        String token = sendInvite(groupId);

        UserContext.setUserId(MEMBER2);
        groupService.joinGroup(groupId, token);

        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertThat(roleOf(MEMBER2, groupId)).isEqualTo("MEMBER");
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertThat(redisTemplate.hasKey(tokenKey(groupId, MEMBER2))).as("一次性邀请码应被删除").isFalse();
        assertInvariants(groupId);

        BizException again = catchThrowableOfType(
                () -> groupService.joinGroup(groupId, token), BizException.class);
        assertThat(again.getCode()).isEqualTo(QuizNestErrorCode.NOT_GROUP_JOIN_TOKEN.getCode());
        assertInvariants(groupId);
    }

    @Test
    @DisplayName("凭邀请码加入：错误码不消费邀请码，正确码仍可加入")
    void joinWithWrongToken_keepsValidToken() {
        long groupId = createInviteGroup();
        String token = sendInvite(groupId);
        String wrong = token.equals("000000") ? "000001" : "000000";

        UserContext.setUserId(MEMBER2);
        BizException ex = catchThrowableOfType(
                () -> groupService.joinGroup(groupId, wrong), BizException.class);
        assertThat(ex.getCode()).isEqualTo(QuizNestErrorCode.NOT_GROUP_JOIN_TOKEN.getCode());
        assertThat(rowCount("group_id = ? and user_id = ?", groupId, MEMBER2)).isZero();
        assertThat(memberCountOf(groupId)).isEqualTo(1L);
        assertThat(redisTemplate.hasKey(tokenKey(groupId, MEMBER2))).isTrue();

        groupService.joinGroup(groupId, token);
        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertInvariants(groupId);
    }

    @Test
    @DisplayName("凭邀请码加入：原 PENDING 占位就地转 APPROVED，member_count 只加一次")
    void joinWithInviteToken_pendingPlaceholder_upgraded() {
        long groupId = insertInviteGroup(501L, ROOT, 1L);
        insertMember(MEMBER2, groupId, "MEMBER", "PENDING");
        String token = sendInvite(groupId);

        UserContext.setUserId(MEMBER2);
        groupService.joinGroup(groupId, token);

        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertThat(rowCount("group_id = ? and user_id = ?", groupId, MEMBER2)).isEqualTo(1);
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertInvariants(groupId);
    }

    @Test
    @DisplayName("凭邀请码加入：原 REJECTED 占位就地转 APPROVED")
    void joinWithInviteToken_rejectedPlaceholder_upgraded() {
        long groupId = insertInviteGroup(502L, ROOT, 1L);
        insertMember(MEMBER2, groupId, "MEMBER", "REJECTED");
        String token = sendInvite(groupId);

        UserContext.setUserId(MEMBER2);
        groupService.joinGroup(groupId, token);

        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertInvariants(groupId);
    }

    @Test
    @DisplayName("自由加入：PENDING/REJECTED 占位改为 APPROVED，不产生第二条成员记录")
    void freeJoin_upgradesPlaceholderWithoutDuplicateRow() {
        insertGroupWithType(503L, ROOT, 1L, "FREE");
        insertMember(ROOT, 503L, "ROOT", "APPROVED");
        insertMember(MEMBER2, 503L, "MEMBER", "PENDING");

        UserContext.setUserId(MEMBER2);
        groupService.joinGroup(503L, null);

        assertThat(statusOf(MEMBER2, 503L)).isEqualTo("APPROVED");
        assertThat(rowCount("group_id = 503 and user_id = ?", MEMBER2)).isEqualTo(1);
        assertThat(memberCountOf(503L)).isEqualTo(2L);
        assertInvariants(503L);

        BizException again = catchThrowableOfType(
                () -> groupService.joinGroup(503L, null), BizException.class);
        assertThat(again.getCode()).isEqualTo(QuizNestErrorCode.HAVE_JOINED.getCode());
        assertInvariants(503L);
    }

    @Test
    @DisplayName("审批加入：PENDING 再次申请返回 SUBMIT_APPLICATION，REJECTED 可重新申请")
    void approvalJoin_handlesPendingAndRejected() {
        insertGroup(504L, ROOT, 1L);
        insertMember(ROOT, 504L, "ROOT", "APPROVED");
        insertMember(MEMBER2, 504L, "MEMBER", "PENDING");

        UserContext.setUserId(MEMBER2);
        BizException pending = catchThrowableOfType(
                () -> groupService.joinGroup(504L, null), BizException.class);
        assertThat(pending.getCode()).isEqualTo(QuizNestErrorCode.SUBMIT_APPLICATION.getCode());

        jdbcTemplate.update("update group_member set join_status = 'REJECTED' "
                + "where group_id = 504 and user_id = ?", MEMBER2);
        groupService.joinGroup(504L, null);
        assertThat(statusOf(MEMBER2, 504L)).isEqualTo("PENDING");
        assertThat(memberCountOf(504L)).isEqualTo(1L);
        assertInvariants(504L);
    }

    // ---------- 压测 ----------

    @Test
    @DisplayName("压测：50 线程同一邀请码并发加入，只有 1 次成功且计数不漂移")
    void concurrentJoin_sameToken_exactlyOneWinner() throws InterruptedException {
        long groupId = createInviteGroup();
        String token = sendInvite(groupId);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                UserContext.setUserId(MEMBER2);
                groupService.joinGroup(groupId, token);
            });
        }
        LoadResult result = runConcurrently(tasks, 32);

        assertThat(result.success()).as("同一邀请码并发加入只能成功 1 次").isEqualTo(1);
        assertThat(result.failures()).allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));
        assertThat(rowCount("group_id = ? and user_id = ?", groupId, MEMBER2)).isEqualTo(1);
        assertThat(statusOf(MEMBER2, groupId)).isEqualTo("APPROVED");
        assertThat(memberCountOf(groupId)).isEqualTo(2L);
        assertInvariants(groupId);
        System.out.printf("[load] 并发加入 %d 次，成功 %d 次，失败 %d 次%n",
                result.total(), result.success(), result.failures().size());
    }

    @Test
    @DisplayName("压测：PENDING 占位 + 50 线程并发加入，只成功 1 次且 member_count 不重复累加")
    void concurrentJoin_pendingPlaceholder_countedOnce() throws InterruptedException {
        insertGroupWithType(505L, ROOT, 1L, "FREE");
        insertMember(ROOT, 505L, "ROOT", "APPROVED");
        insertMember(MEMBER2, 505L, "MEMBER", "PENDING");

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                UserContext.setUserId(MEMBER2);
                groupService.joinGroup(505L, null);
            });
        }
        LoadResult result = runConcurrently(tasks, 32);

        assertThat(result.success()).as("PENDING 占位并发加入只能成功 1 次").isEqualTo(1);
        assertThat(result.failures()).allSatisfy(t -> assertThat(t).isInstanceOf(BizException.class));
        assertThat(rowCount("group_id = 505 and user_id = ?", MEMBER2)).isEqualTo(1);
        assertThat(memberCountOf(505L)).as("member_count 不能被并发请求重复累加").isEqualTo(2L);
        assertInvariants(505L);
        System.out.printf("[load] PENDING 占位并发加入 %d 次，成功 %d 次，失败 %d 次%n",
                result.total(), result.success(), result.failures().size());
    }

    @Test
    @DisplayName("压测：50 线程并发发同一邀请，只落 1 条 Outbox 事件（3 秒锁去重）")
    void concurrentSend_onlyOneOutboxEvent() throws InterruptedException {
        long groupId = createInviteGroup();
        stubInviterName("小明");

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                UserContext.setUserId(ROOT);
                groupService.sendJoinTokenById(groupId, MEMBER2, null);
            });
        }
        LoadResult result = runConcurrently(tasks, 32);

        assertThat(result.failures()).isEmpty();
        assertThat(result.success()).isEqualTo(50);
        assertThat(outboxCount()).as("并发发送应被 3 秒锁去重为 1 封").isEqualTo(1);
        assertThat(currentToken(groupId, MEMBER2)).matches("\\d{6}");
        System.out.printf("[load] 并发发送 %d 次，成功 %d 次，Outbox 事件 %d 条%n",
                result.total(), result.success(), outboxCount());
    }

    // ---------- 夹具 ----------

    private long createInviteGroup() {
        UserContext.setUserId(ROOT);
        return groupService.cerateGroup(createGroupDto("算法小组", "INVITE")).getGroupId();
    }

    private CreateGroupDto createGroupDto(String name, String type) {
        CreateGroupDto dto = new CreateGroupDto();
        dto.setGroupName(name);
        dto.setGroupDescription("邀请码集成测试小组");
        dto.setGroupType(type);
        dto.setToken("create-" + UUID.randomUUID());
        return dto;
    }

    private long insertInviteGroup(long groupId, long rootId, long memberCount) {
        insertGroupWithType(groupId, rootId, memberCount, "INVITE");
        insertMember(rootId, groupId, "ROOT", "APPROVED");
        return groupId;
    }

    private void insertGroupWithType(long groupId, long rootId, long memberCount, String groupType) {
        jdbcTemplate.update("insert into `group`(group_id, group_name, user_id, group_type, member_count) "
                + "values(?, ?, ?, ?, ?)", groupId, "g" + groupId, rootId, groupType, memberCount);
    }

    private void stubInviterName(String nickName) {
        UserDto user = new UserDto();
        user.setNickName(nickName);
        user.setUserName("xiaoming");
        when(userFeignClient.getUserInfo(anyLong())).thenReturn(Result.success(user));
    }

    private String sendInvite(long groupId) {
        stubInviterName("小明");
        UserContext.setUserId(ROOT);
        groupService.sendJoinTokenById(groupId, MEMBER2, "一起刷题");
        return currentToken(groupId, MEMBER2);
    }

    private String currentToken(long groupId, long userId) {
        Object value = redisTemplate.opsForValue().get(tokenKey(groupId, userId));
        return value == null ? null : String.valueOf(value);
    }

    private String tokenKey(long groupId, long userId) {
        return RedisContext.JOIN_GROUP_TOKEN_KEY + ":" + userId + ":" + groupId;
    }

    private String sendLockKey(long groupId, long userId) {
        return RedisContext.JOIN_GROUP_TOKEN_SEND_LOCK_KEY + ":" + userId + ":" + groupId;
    }

    private long outboxCount() {
        Long count = jdbcTemplate.queryForObject("select count(*) from outboxpro_outbox", Long.class);
        return count == null ? 0 : count;
    }

    private record LoadResult(int total, int success, List<Throwable> failures) {
    }

    private LoadResult runConcurrently(List<Runnable> tasks, int threads) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        AtomicInteger success = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (Runnable task : tasks) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                    success.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    UserContext.clear();
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).as("并发任务应在 120s 内跑完").isTrue();
        pool.shutdownNow();
        return new LoadResult(tasks.size(), success.get(), new ArrayList<>(failures));
    }
}
