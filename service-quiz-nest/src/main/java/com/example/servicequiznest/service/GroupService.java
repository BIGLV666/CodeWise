package com.example.servicequiznest.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMapper;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.util.GroupUtil;
import lombok.extern.log4j.Log4j2;
import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.until.UserContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Log4j2
@Service
public class GroupService {
    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final RedisTemplate<String,Object> redisTemplate;



    public GroupService(
            GroupMapper groupMapper,
            GroupMemberMapper groupMemberMapper,
            RedisTemplate<String,Object> redisTemplate
    ) {
        this.groupMapper = groupMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 用户创建小组先获取token，token有效期为10分钟
     * token作为幂等性校验，防止用户重复创建小组
     * @return token
     */
    public String getToken(){
        return UUID.randomUUID().toString();
    }

    @Transactional(rollbackFor = Exception.class)
    public Group cerateGroup(CreateGroupDto createGroupDto){
       GroupUtil.checkGroup(createGroupDto);
       boolean tryLock= Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(createGroupDto.getToken(), createGroupDto.getToken(),10, TimeUnit.MINUTES));
       if(!tryLock){
           throw new BizException(QuizNestErrorCode.DUPLICATE_REQUEST);
       }
       Group group=new Group(createGroupDto);
       group.setUserId(UserContext.getUserId());
       GroupMember member =new GroupMember(UserContext.getUserId(),GroupMemberRole.ROOT,group.getGroupId());
       try {
           int g=groupMapper.insert(group);
           int m=groupMemberMapper.insert(member);
           if(g==0||m==0){
               throw new BizException(QuizNestErrorCode.CREATE_FAILED);
           }
       }catch (Exception e){
           if(e instanceof BizException){
               log.warn("插入失败{}",((BizException) e).getCode(),e);
           }else {
               log.error(" cerateGroup 方法插入异常{}",e.getMessage(),e);
           }
           redisTemplate.delete(createGroupDto.getToken());
           throw new BizException(QuizNestErrorCode.CREATE_FAILED, e);
       }
       return group;
    }

    /**
     * @param GroupDto 接收GroupDto并原样修改，所以必须校验groupDto是否有效
     * 同时前端必须返回携带所有数据，并给用户校验否则后端将修改为null。
     * 可以修改小组的角色有role==ADMIN(1, "管理员") or ROOT(2, "超级管理员");
     */
    public Group updateGroup(GroupDto GroupDto){
        GroupUtil.checkGroup(GroupDto);
        Group g=new Group(GroupDto);
        g.setUserId(UserContext.getUserId());

        GroupMember member=groupMemberMapper.selectOne(
                new QueryWrapper<GroupMember>().eq("group_id",g.getGroupId())
                        .eq("user_id",UserContext.getUserId())
        );
        if(!GroupMemberRole.ROOT.equals(member.getRole())&&!GroupMemberRole.ADMIN.equals(member.getRole())){
            throw new BizException(QuizNestErrorCode.NO_PERMISSION_UPDATE_GROUP);
        }
        int r=groupMapper.updateGroup(g);
        if(r==0){
            throw new BizException(QuizNestErrorCode.UPDATE_FAILED);
        }
        return g;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean leaveGroup(Long groupId,Long lastRoot){
        GroupMember member=groupMemberMapper.selectOne(
                new QueryWrapper<GroupMember>().eq("user_id",UserContext.getUserId()).eq("group_id",groupId)
        );
        if(member==null){
            throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        }

        /*
          如果该用户为root用户。那么不可以直接退出，应从其他管理员中选择一个晋升，
          假如不存在管理员则从普通成员中获取一个最近最活跃的成员晋升。亦可由root用户指定继承人后退出。
         */
        //TODO 待晋升算法实现后此处需要同步修改
        lastRoot=getLastRoot(lastRoot);
        int r;
        if(GroupMemberRole.ROOT.equals(member.getRole())){
             r= groupMapper.rootLeaveGroup(groupId,UserContext.getUserId());
             // 最后一名成员退出后小组不再有意义，顺手删掉空组。
             // 不能并进 rootLeaveGroup 的同一条 SQL：MySQL 多表 DELETE 禁止子查询引用被删的目标表（ERROR 1093）。
             groupMapper.delete(new QueryWrapper<Group>()
                     .eq("group_id", groupId)
                     .notExists("select 1 from group_member gm where gm.group_id = `group`.group_id"));
        }else {
             // 普通成员退出：SQL 按 (group_id, user_id) 删除，必须传退出者本人 id（SQL 读不到 UserContext）
             r=groupMapper.leaveGroup(groupId,UserContext.getUserId());
        }
        if(r==0){
            throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
        }
        return true;
    }

    /**
     * 晋升算法实现，删除小组成员时必须调用判断是否需要晋升。
     * @param lastRoot
     * @return
     */
    private Long getLastRoot(Long lastRoot){
        //TODO 晋升算法，如果lastRoot！=null,即存在该用户于小组中则晋升该用户，不再小组或==null则执行晋升算法。
        return  lastRoot;
    }






}
