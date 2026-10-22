package com.example.servicequiznest.service;

import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.support.AbstractGroupIntegrationTest;
import com.example.servicequiznest.support.TestContainers;
import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 建组（{@link GroupService#cerateGroup}）的真库 + 真 Redis 集成测试。
 *
 * <p>覆盖：建组写 root 成员且 member_count=1（创建人自己算成员）、token 幂等（重复提交 409）、
 * 参数校验错误码、以及“建完就能正常退出”的端到端链路。</p>
 */
class GroupCreateGroupIntegrationTest extends AbstractGroupIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = TestContainers.newMySql();

    @Container
    static final GenericContainer<?> REDIS = TestContainers.newRedis();

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        TestContainers.registerMySql(registry, MYSQL);
        TestContainers.registerRedis(registry, REDIS);
    }

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private CreateGroupDto dto(String groupName, String groupType, String token) {
        CreateGroupDto dto = new CreateGroupDto();
        dto.setGroupName(groupName);
        dto.setGroupDescription("集成测试小组");
        dto.setGroupType(groupType);
        dto.setToken(token);
        return dto;
    }

    @Test
    @DisplayName("建组：写入 root 成员记录（group_id 正确、APPROVED）、member_count=1、token 被占用")
    void cerateGroup_createsRootMemberAndMemberCountOne() {
        UserContext.setUserId(ROOT);
        Group group = groupService.cerateGroup(dto("算法小组", "APPROVAL", "token-create-1"));

        assertThat(group.getGroupId()).as("自增主键应回填").isNotNull();
        assertThat(ownerOf(group.getGroupId())).isEqualTo(ROOT);
        assertThat(memberCountOf(group.getGroupId())).isEqualTo(1L);
        assertThat(roleOf(ROOT, group.getGroupId())).isEqualTo("ROOT");
        assertThat(statusOf(ROOT, group.getGroupId())).isEqualTo("APPROVED");
        assertThat(redisTemplate.hasKey("token-create-1")).as("一次性创建凭证应被占用").isTrue();
        assertInvariants(group.getGroupId());
    }

    @Test
    @DisplayName("建组：同一个 token 重复提交按重复请求拒绝，且不会多建一个组")
    void cerateGroup_duplicateToken_rejected() {
        UserContext.setUserId(ROOT);
        groupService.cerateGroup(dto("算法小组", "APPROVAL", "token-dup"));

        BizException ex = catchThrowableOfType(
                () -> groupService.cerateGroup(dto("算法小组", "APPROVAL", "token-dup")), BizException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getCode()).isEqualTo(QuizNestErrorCode.DUPLICATE_REQUEST.getCode());
        Long groups = jdbcTemplate.queryForObject("select count(*) from `group`", Long.class);
        assertThat(groups).as("重复提交不应多建组").isEqualTo(1L);
    }

    @Test
    @DisplayName("建组：缺 token / 缺小组名 / 加入方式非法，分别给出对应参数错误码")
    void cerateGroup_invalidParams_rejected() {
        UserContext.setUserId(ROOT);

        BizException noToken = catchThrowableOfType(
                () -> groupService.cerateGroup(dto("算法小组", "APPROVAL", "  ")), BizException.class);
        assertThat(noToken).isNotNull();
        assertThat(noToken.getCode()).isEqualTo(QuizNestErrorCode.CREATE_TOKEN_REQUIRED.getCode());

        BizException noName = catchThrowableOfType(
                () -> groupService.cerateGroup(dto(" ", "APPROVAL", "token-noname")), BizException.class);
        assertThat(noName).isNotNull();
        assertThat(noName.getCode()).isEqualTo(QuizNestErrorCode.GROUP_NAME_REQUIRED.getCode());

        BizException badType = catchThrowableOfType(
                () -> groupService.cerateGroup(dto("算法小组", "SECRET", "token-badtype")), BizException.class);
        assertThat(badType).isNotNull();
        assertThat(badType.getCode()).isEqualTo(QuizNestErrorCode.GROUP_TYPE_INVALID.getCode());

        Long groups = jdbcTemplate.queryForObject("select count(*) from `group`", Long.class);
        assertThat(groups).as("参数非法时不应落库").isZero();
    }

    @Test
    @DisplayName("端到端：建组后创建人退出，小组连同成员记录一起删除")
    void cerateGroup_thenRootLeave_purgesGroup() {
        UserContext.setUserId(ROOT);
        Group group = groupService.cerateGroup(dto("算法小组", "FREE", "token-e2e"));
        long groupId = group.getGroupId();

        assertThat(groupService.leaveGroup(groupId, null)).isTrue();

        assertGroupPurged(groupId);
    }
}