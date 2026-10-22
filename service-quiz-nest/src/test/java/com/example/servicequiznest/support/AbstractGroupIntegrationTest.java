package com.example.servicequiznest.support;

import com.example.servicequiznest.service.GroupService;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小组退出/晋升集成测试基类：真 MySQL 8.4（Testcontainers，本地无 Docker 整类跳过）。
 *
 * <p>只放公共夹具与不变量断言，容器与 {@code @DynamicPropertySource} 留在各子类
 * （Testcontainers 的 JUnit 扩展在 afterAll 停容器，跨类共享静态容器会失败）。</p>
 *
 * <p>核心不变量（每次操作后都要成立）：</p>
 * <ol>
 *   <li>每组恰好 1 条 {@code role='ROOT'} 成员记录，且与 {@code group.user_id} 一致；</li>
 *   <li>{@code group.member_count} == 该组 APPROVED 成员数（含 root）；</li>
 *   <li>没有成员的小组必须被删除（不留孤儿组）。</li>
 * </ol>
 */
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
public abstract class AbstractGroupIntegrationTest {

    protected static final long ROOT = 1001L;
    protected static final long ADMIN = 1002L;
    protected static final long MEMBER = 1003L;
    protected static final long MEMBER2 = 1004L;
    protected static final long PENDING = 1005L;

    @Autowired
    protected GroupService groupService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("delete from group_member");
        jdbcTemplate.execute("delete from `group`");
        UserContext.clear();
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    // ---------- 夹具 ----------

    protected void insertGroup(long groupId, long rootId, long memberCount) {
        jdbcTemplate.update("insert into `group`(group_id, group_name, user_id, group_type, member_count) "
                + "values(?, ?, ?, 'APPROVAL', ?)", groupId, "g" + groupId, rootId, memberCount);
    }

    protected void insertMember(long userId, long groupId, String role, String joinStatus) {
        insertMember(userId, groupId, role, joinStatus, "2026-01-01 00:00:00");
    }

    protected void insertMember(long userId, long groupId, String role, String joinStatus, String updateTime) {
        jdbcTemplate.update("insert into group_member(user_id, group_id, role, join_status, update_time) "
                + "values(?, ?, ?, ?, ?)", userId, groupId, role, joinStatus, updateTime);
    }

    // ---------- 查询 ----------

    protected Long ownerOf(long groupId) {
        return jdbcTemplate.queryForObject("select user_id from `group` where group_id = ?", Long.class, groupId);
    }

    protected Long memberCountOf(long groupId) {
        return jdbcTemplate.queryForObject("select member_count from `group` where group_id = ?", Long.class, groupId);
    }

    protected long approvedCountOf(long groupId) {
        Long n = jdbcTemplate.queryForObject(
                "select count(*) from group_member where group_id = ? and join_status = 'APPROVED'", Long.class, groupId);
        return n == null ? 0 : n;
    }

    protected long rowCount(String where, Object... args) {
        Long n = jdbcTemplate.queryForObject("select count(*) from group_member where " + where, Long.class, args);
        return n == null ? 0 : n;
    }

    protected String roleOf(long userId, long groupId) {
        return jdbcTemplate.queryForObject(
                "select role from group_member where group_id = ? and user_id = ?", String.class, groupId, userId);
    }

    protected String statusOf(long userId, long groupId) {
        return jdbcTemplate.queryForObject(
                "select join_status from group_member where group_id = ? and user_id = ?", String.class, groupId, userId);
    }

    protected long groupCount(long groupId) {
        Long n = jdbcTemplate.queryForObject("select count(*) from `group` where group_id = ?", Long.class, groupId);
        return n == null ? 0 : n;
    }

    // ---------- 不变量 ----------

    /** 每组至多/恰好一个 ROOT，且 group.user_id 与之一致；member_count 与 APPROVED 人数一致。 */
    protected void assertInvariants(long groupId) {
        assertThat(groupCount(groupId)).as("小组 %s 应仍存在", groupId).isEqualTo(1);
        Long owner = ownerOf(groupId);
        assertThat(rowCount("group_id = ? and role = 'ROOT'", groupId))
                .as("小组 %s 的 ROOT 成员记录数", groupId).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select user_id from group_member where group_id = ? and role = 'ROOT'", Long.class, groupId))
                .as("小组 %s 的 ROOT 成员应等于 group.user_id", groupId).isEqualTo(owner);
        assertThat(memberCountOf(groupId))
                .as("小组 %s 的 member_count 应等于 APPROVED 成员数", groupId).isEqualTo(approvedCountOf(groupId));
        assertThat(approvedCountOf(groupId)).as("小组 %s 不应留下没有成员的孤儿组", groupId).isPositive();
    }

    /** 小组应被彻底删除：组记录与全部成员记录（含 PENDING/REJECTED 占位）都不存在。 */
    protected void assertGroupPurged(long groupId) {
        assertThat(groupCount(groupId)).as("小组 %s 应被删除", groupId).isZero();
        assertThat(rowCount("group_id = ?", groupId)).as("小组 %s 的成员记录应全部清理", groupId).isZero();
    }
}