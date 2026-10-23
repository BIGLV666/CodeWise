package com.example.servicequiznest.service;

import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.mapper.GroupMapper;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退出小组时题库清理的触发边界：只有「最后一人退出、小组真的被删」才清理；
 * 有人继承 root 时题目资产必须保留。
 */
@ExtendWith(MockitoExtension.class)
class GroupServiceLeaveCleanupTest {

    private static final long GROUP_ID = 88L;
    private static final long USER_ID = 9L;
    private static final long HEIR_ID = 10L;

    @Mock
    private GroupMapper groupMapper;

    @Mock
    private GroupMemberMapper groupMemberMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private OutboxProPublisher outboxPublisher;

    @Mock
    private UserFeignClient userFeignClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private GroupQuestionService groupQuestionService;

    private GroupService groupService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
        groupService = new GroupService(
                groupMapper, groupMemberMapper, redisTemplate, outboxPublisher,
                userFeignClient, transactionTemplate, groupQuestionService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void lastRootLeavingDeletesGroupAndCleansQuestions() {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        when(groupMapper.selectHeirUserId(GROUP_ID, USER_ID)).thenReturn(null);
        when(groupMapper.decMemberCount(GROUP_ID)).thenReturn(1);
        when(groupMapper.deleteRootMember(GROUP_ID, USER_ID)).thenReturn(1);
        when(groupMapper.deleteGroupIfNoApprovedMember(GROUP_ID)).thenReturn(1);
        when(groupMapper.deleteAllMembers(GROUP_ID)).thenReturn(1);

        assertThat(groupService.leaveGroup(GROUP_ID, null)).isTrue();

        verify(groupQuestionService).deleteGroupQuestions(GROUP_ID);
    }

    @Test
    void rootHandoverKeepsGroupQuestions() {
        when(groupMemberMapper.selectOne(any())).thenReturn(rootMember());
        when(groupMapper.selectHeirUserId(GROUP_ID, USER_ID)).thenReturn(HEIR_ID);
        when(groupMapper.casRootOwner(GROUP_ID, USER_ID, HEIR_ID)).thenReturn(1);
        when(groupMapper.promoteMemberToRoot(GROUP_ID, HEIR_ID)).thenReturn(1);
        when(groupMapper.deleteRootMember(GROUP_ID, USER_ID)).thenReturn(1);
        when(groupMapper.decMemberCount(GROUP_ID)).thenReturn(1);

        assertThat(groupService.leaveGroup(GROUP_ID, null)).isTrue();

        verify(groupQuestionService, never()).deleteGroupQuestions(any());
    }

    @Test
    void nonRootFallbackDeletionAlsoCleansQuestions() {
        GroupMember member = rootMember();
        member.setRole(GroupMemberRole.MEMBER);
        when(groupMemberMapper.selectOne(any())).thenReturn(member);
        when(groupMapper.decMemberCount(GROUP_ID)).thenReturn(1);
        when(groupMapper.leaveGroup(GROUP_ID, USER_ID)).thenReturn(1);
        // 并发晋升兜底：非 root 退出后组里已无人，小组被删除
        when(groupMapper.deleteGroupIfNoApprovedMember(GROUP_ID)).thenReturn(1);
        when(groupMapper.deleteAllMembers(GROUP_ID)).thenReturn(1);

        assertThat(groupService.leaveGroup(GROUP_ID, null)).isTrue();

        verify(groupQuestionService).deleteGroupQuestions(GROUP_ID);
    }

    private static GroupMember rootMember() {
        GroupMember member = new GroupMember();
        member.setUserId(USER_ID);
        member.setGroupId(GROUP_ID);
        member.setRole(GroupMemberRole.ROOT);
        member.setJoinStatus(GroupJoinStatus.APPROVED);
        return member;
    }
}
