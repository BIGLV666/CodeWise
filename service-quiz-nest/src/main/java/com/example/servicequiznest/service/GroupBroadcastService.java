package com.example.servicequiznest.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMapper;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.util.convert.BroadCastConvert;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.quizNest.BroadCast;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GroupBroadcastService {
    private final OutboxProPublisher outboxProPublisher;
    private final GroupMemberMapper groupMemberMapper;
    private final UserFeignClient userFeignClient;
    private final GroupMapper groupMapper;
    public GroupBroadcastService(
            GroupMapper groupMapper,
            UserFeignClient userFeignClient,
            OutboxProPublisher outboxProPublisher,
            GroupMemberMapper groupMemberMapper
    ) {
        this.groupMapper = groupMapper;
        this.userFeignClient = userFeignClient;
        this.outboxProPublisher = outboxProPublisher;
        this.groupMemberMapper = groupMemberMapper;
    }

    /**
     * 发送小组广播，广播仅允许管理发送
     * <B>注意：发送人本身不在发送目标中</B>
     * @param context 发送的消息内容
     * @param groupId 需要发送的小组id
     */
    public void sendGroupBroadCastService(String context,Long groupId) {
        if (groupId == null) {
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        if (context == null||context.isBlank()) {
            throw new BizException(QuizNestErrorCode.GROUP_BROADCAST_NOT_IsEMPTY);
        }
        GroupMember groupMember = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                .eq("user_id", UserContext.getUserId())
                .eq("group_id", groupId));
        if (groupMember == null) throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        if (groupMember.getRole().equals(GroupMemberRole.MEMBER)) {
            throw new BizException(QuizNestErrorCode.NO_PERMISSION_SEND_BROADCAST);
        }
        Group group=groupMapper.selectById(groupId);
        if(group==null){
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP);
        }

        List<Long>members=groupMemberMapper.selectList(
                new QueryWrapper<GroupMember>()
                        .eq("group_id", groupId)
                        .eq("join_status", GroupJoinStatus.APPROVED.name())
        ).stream().map(GroupMember::getUserId)
                .filter(a->!a.equals(UserContext.getUserId()))
                .toList();

        //假如组员仅仅有自己，那视为空组，不发送
        if(members.isEmpty())return;
        String userName=null;
        try {
            Result<UserDto> body=userFeignClient.getUserInfo(UserContext.getUserId());
            if(body==null||body.getCode()!=200||body.getData()==null){
                throw new IllegalAccessException("获取用户失败，由catch兜底");
            }
            userName=body.getData().getNickName()==null?body.getData().getUserName():body.getData().getNickName();
        }catch (Exception e){
            //获取用户信息失败不阻碍广播发送，使用管理员兜底即可
            log.error("发送广播获取用户数据失败，userid{}",UserContext.getUserId(),e);
        }

        outboxProPublisher.publish(
                BroadCast.class,
                BroadCastConvert.ToBroadCast(groupId,group.getGroupName(),members,UserContext.getUserId(),userName==null?"管理员":userName,context)
                );
    }



}
