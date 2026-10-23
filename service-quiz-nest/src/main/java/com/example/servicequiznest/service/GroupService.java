package com.example.servicequiznest.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.enums.GroupType;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMapper;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.util.GroupUtil;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.quizNest.JoinToken;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 小组业务：建组/改组、退出与 root 晋升、自由/审批/邀请三种加入方式。
 *
 * <p>邀请码链路：ROOT/ADMIN 在 {@link #sendJoinTokenById} 中生成 6 位邀请码，
 * 先写入 Redis（{@link RedisContext#JOIN_GROUP_TOKEN_TTL_SECONDS} 有效），再在短事务内
 * 经 OutboxPro 投递 {@code GROUP_JOIN_TOKEN} 事件；service-message 消费后写
 * notification_center 站内信并尽力 WebSocket 推送。邀请码与被邀请人 + 小组绑定，
 * 一次性使用（加入成功后删除），重新发送会覆盖旧码。</p>
 */
@Slf4j
@Service
public class GroupService {

    /** 6 位邀请码，取值范围 000000-999999。 */
    private static final int JOIN_TOKEN_BOUND = 1_000_000;
    /** 邀请留言最大长度，避免把站内信正文撑爆。 */
    private static final int INVITE_CONTEXT_MAX_LENGTH = 200;
    /** 加入事务遇到 MySQL 死锁/锁等待超时的最大尝试次数（含首次）。 */
    private static final int JOIN_MAX_ATTEMPTS = 3;
    /** 站内信时间展示格式：消费端 EnvelopeCodec 未注册 JavaTimeModule，因此事件里用字符串。 */
    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final OutboxProPublisher outboxPublisher;
    private final UserFeignClient userFeignClient;
    private final TransactionTemplate transactionTemplate;
    private final GroupQuestionService groupQuestionService;

    public GroupService(
            GroupMapper groupMapper,
            GroupMemberMapper groupMemberMapper,
            RedisTemplate<String, Object> redisTemplate,
            OutboxProPublisher outboxPublisher,
            UserFeignClient userFeignClient,
            TransactionTemplate transactionTemplate,
            GroupQuestionService groupQuestionService
    ) {
        this.groupMapper = groupMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.redisTemplate = redisTemplate;
        this.outboxPublisher = outboxPublisher;
        this.userFeignClient = userFeignClient;
        this.transactionTemplate = transactionTemplate;
        this.groupQuestionService = groupQuestionService;
    }

    /**
     * 生成 6 位随机邀请码。
     *
     * <p>上界必须是 1_000_000（{@link SecureRandom#nextInt(int)} 上界开区间），
     * 否则 {@code %06d} 会在 7 位数时原样输出，产生 7 位邀请码。</p>
     */
    private String generateJoinToken() {
        return String.format("%06d", SECURE_RANDOM.nextInt(JOIN_TOKEN_BOUND));
    }

    /**
     * 用户创建小组先获取 token，token 有效期为 10 分钟。
     * token 作为幂等性校验，防止用户重复创建小组。
     *
     * @return 一次性创建凭证
     */
    public String getToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * 创建小组：创建人自动成为 ROOT 成员，member_count 从 1 开始。
     *
     * <p>token 是前端先调 {@link #getToken()} 拿到的一次性凭证，用 Redis SETNX 占位；
     * 占位失败说明同一 token 已提交过，按重复请求拒绝。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Group cerateGroup(CreateGroupDto createGroupDto) {
        GroupUtil.checkGroup(createGroupDto);
        boolean tryLock;
        try {
            tryLock = Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(
                    createGroupDto.getToken(), createGroupDto.getToken(), 10, TimeUnit.MINUTES));
        } catch (RuntimeException e) {
            log.error("创建凭证占位失败,userId={}", UserContext.getUserId(), e);
            throw new BizException(QuizNestErrorCode.CREATE_FAILED, e);
        }
        if (!tryLock) {
            throw new BizException(QuizNestErrorCode.DUPLICATE_REQUEST);
        }
        Group group = new Group(createGroupDto);
        group.setUserId(UserContext.getUserId());
        try {
            int g = groupMapper.insert(group);
            // 成员记录必须在 group 插入之后构造：group_id 是自增主键，插入前拿不到，
            // 历史写法在插入前就 new 出来，会把 group_id=NULL 写进 NOT NULL 列导致建组永远失败。
            GroupMember member = new GroupMember(UserContext.getUserId(), GroupMemberRole.ROOT, group.getGroupId());
            int m = groupMemberMapper.insert(member);
            if (g == 0 || m == 0) {
                throw new BizException(QuizNestErrorCode.CREATE_FAILED);
            }
        } catch (Exception e) {
            if (e instanceof BizException) {
                log.warn("插入失败{}", ((BizException) e).getCode(), e);
            } else {
                log.error("cerateGroup 方法插入异常{}", e.getMessage(), e);
            }
            deleteQuietly(createGroupDto.getToken());
            throw new BizException(QuizNestErrorCode.CREATE_FAILED, e);
        }
        return group;
    }

    /**
     * 修改小组。接收 {@link GroupDto} 并原样修改，所以前端必须提交全量字段。
     * 可以修改小组的角色只有 ROOT 或 ADMIN。
     *
     * @param groupDto 全量修改 DTO
     * @return 修改后的小组
     */
    public Group updateGroup(GroupDto groupDto) {
        GroupUtil.checkGroup(groupDto);
        Group g = new Group(groupDto);
        g.setUserId(UserContext.getUserId());

        GroupMember member = groupMemberMapper.selectOne(
                new QueryWrapper<GroupMember>().eq("group_id", g.getGroupId())
                        .eq("user_id", UserContext.getUserId())
        );
        if (member == null) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        }
        if (!GroupMemberRole.ROOT.equals(member.getRole()) && !GroupMemberRole.ADMIN.equals(member.getRole())) {
            throw new BizException(QuizNestErrorCode.NO_PERMISSION_UPDATE_GROUP);
        }
        int r = groupMapper.updateGroup(g);
        if (r == 0) {
            throw new BizException(QuizNestErrorCode.UPDATE_FAILED);
        }
        return g;
    }

    /**
     * 查询小组基础信息（供加入前确认加入方式、邀请页展示小组名）。
     */
    public Group getGroup(Long groupId) {
        if (groupId == null) {
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP);
        }
        return group;
    }

    /**
     * 退出小组。最后一名的 APPROVED 成员退出时小组一并删除。
     *
     * @param groupId  小组 ID
     * @param lastRoot root 指定的继承人（可空；非 root 退出时忽略）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean leaveGroup(Long groupId, Long lastRoot) {
        if (groupId == null) {
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        Long userId = UserContext.getUserId();
        GroupMember member = groupMemberMapper.selectOne(
                new QueryWrapper<GroupMember>().eq("user_id", userId).eq("group_id", groupId)
        );
        if (member == null) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        }

        // 待审批/已拒绝的占位记录从未计入 member_count，退出时只撤记录、不动计数
        if (!GroupJoinStatus.APPROVED.equals(member.getJoinStatus())) {
            if (groupMapper.leaveGroup(groupId, userId) == 0) {
                throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
            }
            return true;
        }

        if (!GroupMemberRole.ROOT.equals(member.getRole())) {
            // 普通成员退出：先扣人数拿 group 行锁、再删成员记录。锁顺序与晋升路径一致（都先锁 group 行），避免死锁
            if (groupMapper.decMemberCount(groupId) == 0) {
                log.error("普通成员退出失败：成员数扣减失败,userId={},groupId={}", userId, groupId);
                throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
            }
            if (groupMapper.leaveGroup(groupId, userId) == 0) {
                log.error("普通成员退出失败：成员记录不存在,userId={},groupId={}", userId, groupId);
                throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
            }
            // 兜底：并发下自己可能刚被晋升为 root 后再走非 root 分支，此时组里已无人，别留下孤儿组
            if (groupMapper.deleteGroupIfNoApprovedMember(groupId) != 0) {
                groupMapper.deleteAllMembers(groupId);
                groupQuestionService.deleteGroupQuestions(groupId);
            }
            return true;
        }

        /*
          root 退出不能直接走人，必须先把 root 指针交给继承人：
          指定继承人 > 管理员 > 普通成员；都没有说明自己是最后一名成员，人和组一起删。
          整方法一个事务，任何一步失败都抛 BizException 整体回滚，不会留下半成品。
         */
        Long heirId = resolveHeir(groupId, userId, lastRoot);
        if (heirId != null) {
            promoteAndLeave(groupId, userId, heirId);
        } else {
            removeLastRoot(groupId, userId);
        }
        return true;
    }

    /**
     * 选 root 的继承人：优先用 root 指定的继承人（必须是本组 APPROVED 成员、且不是自己），
     * 否则按「管理员优先 → 最近有成员记录变更 → 入库顺序」自动选一个；无人可继承返回 null。
     */
    private Long resolveHeir(Long groupId, Long leaverId, Long designated) {
        if (designated != null && !designated.equals(leaverId)) {
            Long approved = groupMemberMapper.selectCount(new QueryWrapper<GroupMember>()
                    .eq("group_id", groupId)
                    .eq("user_id", designated)
                    .eq("join_status", GroupJoinStatus.APPROVED.name())
                    .ne("role", GroupMemberRole.ROOT.name()));
            if (approved != null && approved > 0) {
                return designated;
            }
            // 指定的继承人已退组/未通过审批：静默回退到自动晋升，不阻断 root 退出
            log.warn("指定的继承人无效，改为自动晋升,groupId={},designated={}", groupId, designated);
        }
        return groupMapper.selectHeirUserId(groupId, leaverId);
    }

    /**
     * 晋升并退出，四步同一事务；任一步命中 0 行即抛异常整体回滚。
     */
    private void promoteAndLeave(Long groupId, Long leaverId, Long heirId) {
        // 1. CAS root 指针：并发下只有一个事务能命中，这是「每组至多一个 ROOT」的保证
        if (groupMapper.casRootOwner(groupId, leaverId, heirId) == 0) {
            log.error("晋升失败：root 指针已被改写,groupId={},leaverId={},heirId={}", groupId, leaverId, heirId);
            throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
        }
        // 2. 继承人成员记录升为 ROOT（0 行说明继承人被并发删除或降级）
        if (groupMapper.promoteMemberToRoot(groupId, heirId) == 0) {
            log.error("晋升失败：继承人已不在组内,groupId={},heirId={}", groupId, heirId);
            throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
        }
        // 3. 删掉退出者的 ROOT 成员记录
        if (groupMapper.deleteRootMember(groupId, leaverId) == 0) {
            log.error("晋升失败：退出者成员记录不存在,groupId={},leaverId={}", groupId, leaverId);
            throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
        }
        // 4. 人数 -1（member_count 口径 = APPROVED 成员数，含 root）
        if (groupMapper.decMemberCount(groupId) == 0) {
            log.error("晋升失败：成员数扣减失败,groupId={}", groupId);
            throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
        }
    }

    /**
     * 最后一名 APPROVED 成员（root）退出：人走组删，连 PENDING/REJECTED 占位一起清理，不留孤儿组。
     */
    private void removeLastRoot(Long groupId, Long leaverId) {
        // 先扣人数拿到 group 行锁，后面「组里是否还有 APPROVED 成员」的判断才不会被并发审批插队
        if (groupMapper.decMemberCount(groupId) == 0) {
            log.error("退出失败：成员数扣减失败,groupId={}", groupId);
            throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
        }
        if (groupMapper.deleteRootMember(groupId, leaverId) == 0) {
            log.error("退出失败：root 成员记录不存在,groupId={},leaverId={}", groupId, leaverId);
            throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
        }
        // 已无 APPROVED 成员才删组；并发中若有成员刚通过审批，这里命中 0 行 → 整体回滚让调用方重试
        if (groupMapper.deleteGroupIfNoApprovedMember(groupId) == 0) {
            log.error("退出失败：小组中仍有其他成员,groupId={}", groupId);
            throw new BizException(QuizNestErrorCode.LEAVE_GROUP_FAILED);
        }
        groupMapper.deleteAllMembers(groupId);
        groupQuestionService.deleteGroupQuestions(groupId);
    }

    /**
     * 用户加入小组。
     *
     * <p>小组是否需要审批由第一次查阅决定，不为毫秒级不一致窗口做复杂降级：同一时刻
     * 加入方式被改掉时，按读到的那一版执行。</p>
     *
     * <p>并发冲突（同一用户/同一小组的行锁竞争）会以 MySQL 死锁形式暴露，
     * 由外层短事务 + 有限重试处理；业务失败（已加入、待审批、邀请码错误）不做重试。</p>
     *
     * @param groupId 小组 ID
     * @param token   邀请码（仅 INVITE 小组需要，可空）
     */
    public void joinGroup(Long groupId, String token) {
        if (groupId == null) {
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        for (int attempt = 1; attempt <= JOIN_MAX_ATTEMPTS; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> doJoin(groupId, token));
                return;
            } catch (ConcurrencyFailureException e) {
                log.warn("加入小组遇到并发冲突，重试 {}/{}: groupId={}", attempt, JOIN_MAX_ATTEMPTS, groupId, e);
                if (attempt == JOIN_MAX_ATTEMPTS) {
                    throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED, e);
                }
                sleepBeforeJoinRetry(attempt);
                attempt++;
            }
        }
    }

    /** 单个加入事务：校验小组存在并分派到 FREE/APPROVAL/INVITE 流程。 */
    private void doJoin(Long groupId, String token) {
        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP);
        }
        GroupType groupType = group.getGroupType();
        if (groupType == null) {
            throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED);
        }
        switch (groupType) {
            case FREE -> joinFREEGroup(groupId);
            case APPROVAL -> joinAPPROVALGroup(groupId);
            case INVITE -> joinINVITE(groupId, token);
            default -> throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED);
        }
    }

    private void sleepBeforeJoinRetry(int attempt) {
        try {
            Thread.sleep(10L * attempt);

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED, interrupted);
        }
    }

    /**
     * 加入自由加入的小组：直接写 APPROVED 记录并累加 member_count。
     *
     * <p>历史 PENDING/REJECTED 占位记录会被就地改成 APPROVED（唯一键 (user_id, group_id)
     * 保证一个用户在一个组里只有一条记录），因此不会重复计数。</p>
     */
    private void joinFREEGroup(Long groupId) {
        Long userId = UserContext.getUserId();
        GroupMember existing = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                .eq("user_id", userId).eq("group_id", groupId));
        int affected;
        if (existing != null) {
            if (GroupJoinStatus.APPROVED.equals(existing.getJoinStatus())) {
                throw new BizException(QuizNestErrorCode.HAVE_JOINED);
            }
            // PENDING（待审批）/ REJECTED（曾被拒）：只做条件 UPDATE，不再尝试 INSERT。
            // 先 UPDATE 再 INSERT 的写法在并发下会因唯一键与行锁交叉而频繁死锁。
            affected = groupMemberMapper.approveExistingMember(groupId, userId);
            if (affected == 0) {
                throw new BizException(QuizNestErrorCode.HAVE_JOINED);
            }
        } else {
            GroupMember groupMember = new GroupMember(userId, GroupMemberRole.MEMBER, groupId);
            groupMember.setJoinStatus(GroupJoinStatus.APPROVED);
            groupMember.setJoinTime(LocalDateTime.now());
            groupMember.setUpdateTime(LocalDateTime.now());
            try {
                affected = groupMemberMapper.insert(groupMember);
            } catch (DuplicateKeyException duplicateKeyException) {
                // 预查后仍有并发插入：重新读取并走条件 UPDATE，命中 0 行说明已被别人加入
                GroupMember latest = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                        .eq("user_id", userId).eq("group_id", groupId));
                if (latest == null) {
                    throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED, duplicateKeyException);
                }
                if (GroupJoinStatus.APPROVED.equals(latest.getJoinStatus())) {
                    throw new BizException(QuizNestErrorCode.HAVE_JOINED);
                }
                affected = groupMemberMapper.approveExistingMember(groupId, userId);
                if (affected == 0) {
                    throw new BizException(QuizNestErrorCode.HAVE_JOINED);
                }
            }
        }
        if (affected == 0 || groupMapper.addMemberCount(groupId) == 0) {
            throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED);
        }
    }

    /**
     * 申请加入需要审批的小组：先写 PENDING 占位，等待管理员审批。
     */
    private void joinAPPROVALGroup(Long groupId) {
        Long userId = UserContext.getUserId();
        GroupMember existing = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                .eq("user_id", userId).eq("group_id", groupId));
        int affected;
        if (existing != null) {
            affected = applyApprovalApplication(existing, groupId, userId);
        } else {
            GroupMember groupMember = new GroupMember(userId, GroupMemberRole.MEMBER, groupId);
            groupMember.setJoinStatus(GroupJoinStatus.PENDING);
            groupMember.setUpdateTime(LocalDateTime.now());
            try {
                affected = groupMemberMapper.insert(groupMember);
            } catch (DuplicateKeyException duplicateKeyException) {
                GroupMember latest = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                        .eq("user_id", userId).eq("group_id", groupId));
                if (latest == null) {
                    throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED, duplicateKeyException);
                }
                affected = applyApprovalApplication(latest, groupId, userId);
            }
        }
        if (affected == 0) {
            throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED);
        }
    }

    /**
     * 已有成员记录时的审批制申请逻辑：APPROVED 拒绝重复加入、PENDING 拒绝重复申请、
     * REJECTED 原子地改回 PENDING。
     */
    private int applyApprovalApplication(GroupMember member, Long groupId, Long userId) {
        GroupJoinStatus status = member.getJoinStatus();
        if (GroupJoinStatus.APPROVED.equals(status)) {
            throw new BizException(QuizNestErrorCode.HAVE_JOINED);
        }
        if (GroupJoinStatus.PENDING.equals(status)) {
            throw new BizException(QuizNestErrorCode.SUBMIT_APPLICATION);
        }
        int affected = groupMemberMapper.reapplyRejectedMember(groupId, userId);
        if (affected == 0) {
            GroupMember latest = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                    .eq("user_id", userId).eq("group_id", groupId));
            if (latest != null && GroupJoinStatus.APPROVED.equals(latest.getJoinStatus())) {
                throw new BizException(QuizNestErrorCode.HAVE_JOINED);
            }
            throw new BizException(QuizNestErrorCode.SUBMIT_APPLICATION);
        }
        return affected;
    }

    /**
     * 凭邀请码加入仅邀请小组。
     *
     * <p>邀请码与 (被邀请人, 小组) 绑定，校验通过后走与自由加入相同的落库逻辑；
     * 加入事务提交后才删除邀请码——加入失败（如重复加入）时邀请码仍然有效。</p>
     */
    private void joinINVITE(Long groupId, String token) {
        String normalized = token == null ? null : token.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new BizException(QuizNestErrorCode.NOT_GROUP_JOIN_TOKEN);
        }
        Long userId = UserContext.getUserId();
        boolean valid;
        try {
            valid = checkJoinToken(userId, normalized, groupId);
        } catch (RuntimeException e) {
            log.error("校验邀请码失败,userId={},groupId={}", userId, groupId, e);
            throw new BizException(QuizNestErrorCode.JOIN_GROUP_FAILED, e);
        }
        if (!valid) {
            throw new BizException(QuizNestErrorCode.NOT_GROUP_JOIN_TOKEN);
        }
        joinFREEGroup(groupId);
        deleteJoinTokenAfterCommit(userId, groupId);
    }

    private boolean checkJoinToken(Long userId, String token, Long groupId) {
        Object stored = redisTemplate.opsForValue().get(joinTokenKey(userId, groupId));
        return stored != null && token.equals(String.valueOf(stored));
    }

    private String joinTokenKey(Long userId, Long groupId) {
        return RedisContext.JOIN_GROUP_TOKEN_KEY + ":" + userId + ":" + groupId;
    }

    private String joinTokenSendLockKey(Long userId, Long groupId) {
        return RedisContext.JOIN_GROUP_TOKEN_SEND_LOCK_KEY + ":" + userId + ":" + groupId;
    }

    /**
     * 加入成功的事务提交后再删邀请码：若事务回滚（例如并发下唯一键冲突），
     * 邀请码仍然保留，被邀请人可重试。
     */
    private void deleteJoinTokenAfterCommit(Long userId, Long groupId) {
        String key = joinTokenKey(userId, groupId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        redisTemplate.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("邀请码已使用但删除失败，等待 TTL 自然过期: key={}", key, e);
                    }
                }
            });
            return;
        }
        redisTemplate.delete(key);
    }

    /**
     * 发送邀请码站内信。
     *
     * <p>权限：只有本组 APPROVED 的 ROOT/ADMIN 可以邀请；仅邀请制小组才需要邀请码。
     * 同一 (被邀请人, 小组) 3 秒内只发一封（防双击/并发重试），锁过期后重新发送会生成
     * 新码并覆盖旧码。邀请码先落 Redis，再在事务内经 Outbox 投递事件；投递失败会清理
     * Redis，保证不会出现「码已生效但邀请信永远发不出去」。</p>
     *
     * @param groupId  小组 ID
     * @param toUserId 被邀请人 ID
     * @param content  邀请人留言，可空
     */
    public void sendJoinTokenById(Long groupId, Long toUserId, String content) {
        if (groupId == null) {
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        if (toUserId == null) {
            throw new BizException(QuizNestErrorCode.NOT_USERID_SEND_JOIN_TOKEN);
        }
        Long inviterId = UserContext.getUserId();
        if (inviterId == null) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        }
        if (inviterId.equals(toUserId)) {
            throw new BizException(QuizNestErrorCode.CANNOT_INVITE_SELF);
        }

        GroupMember inviter = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                .eq("user_id", inviterId).eq("group_id", groupId));
        if (inviter == null || !GroupJoinStatus.APPROVED.equals(inviter.getJoinStatus())) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        }
        if (!GroupMemberRole.ROOT.equals(inviter.getRole()) && !GroupMemberRole.ADMIN.equals(inviter.getRole())) {
            throw new BizException(QuizNestErrorCode.NO_PERMISSION_SEND_JOIN_TOKEN);
        }

        Group group = groupMapper.selectById(groupId);
        if (group == null) {
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP);
        }
        if (!GroupType.INVITE.equals(group.getGroupType())) {
            throw new BizException(QuizNestErrorCode.GROUP_NOT_INVITE);
        }

        Long alreadyMember = groupMemberMapper.selectCount(new QueryWrapper<GroupMember>()
                .eq("group_id", groupId)
                .eq("user_id", toUserId)
                .eq("join_status", GroupJoinStatus.APPROVED.name()));
        if (alreadyMember != null && alreadyMember > 0) {
            throw new BizException(QuizNestErrorCode.INVITEE_ALREADY_MEMBER);
        }

        String context = content == null ? null : content.trim();
        if (context != null && context.length() > INVITE_CONTEXT_MAX_LENGTH) {
            throw new BizException(QuizNestErrorCode.INVITE_CONTEXT_TOO_LONG);
        }
        if (context != null && context.isEmpty()) {
            context = null;
        }

        // 远程调用放在发送锁之外：Feign 慢时不会占着 3 秒锁，避免锁过期后并发发送
        // 生成两个码、而先发出的站内信里是已被覆盖的旧码。
        String inviterName = resolveInviterName(inviterId);

        String lockKey = joinTokenSendLockKey(toUserId, groupId);
        try {
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, "1", RedisContext.JOIN_GROUP_TOKEN_SEND_LOCK_SECONDS, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(locked)) {
                log.info("邀请码重复发送，跳过本次请求: inviterId={}, toUserId={}, groupId={}",
                        inviterId, toUserId, groupId);
                return;
            }
        } catch (RuntimeException e) {
            log.error("获取邀请码发送锁失败,inviterId={},toUserId={},groupId={}",
                    inviterId, toUserId, groupId, e);
            throw new BizException(QuizNestErrorCode.SEND_JOIN_TOKEN_FAILED, e);
        }

        String tokenKey = joinTokenKey(toUserId, groupId);
        String token = generateJoinToken();
        try {
            redisTemplate.opsForValue().set(
                    tokenKey, token, RedisContext.JOIN_GROUP_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

            JoinToken joinToken = JoinToken.builder()
                    .messageId("quiz:join-token:" + groupId + ":" + toUserId + ":" + token)
                    .token(token)
                    .groupId(groupId)
                    .toUserId(toUserId)
                    .context(context)
                    .groupName(group.getGroupName())
                    .userName(inviterName)
                    .sendTime(LocalDateTime.now().format(MESSAGE_TIME_FORMATTER))
                    .build();
            // 只把 Outbox 写入放进短事务；Feign 取昵称等远程调用留在事务外，避免长事务占连接
            transactionTemplate.executeWithoutResult(status ->
                    outboxPublisher.publish(EventTypes.GROUP_JOIN_TOKEN, joinToken));
        } catch (RuntimeException e) {
            log.error("邀请码发送失败，清理 Redis 后重试: inviterId={}, toUserId={}, groupId={}",
                    inviterId, toUserId, groupId, e);
            deleteQuietly(tokenKey);
            deleteQuietly(lockKey);
            throw new BizException(QuizNestErrorCode.SEND_JOIN_TOKEN_FAILED, e);
        }
    }

    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("Redis key 清理失败，等待 TTL 自然过期: key={}", key, e);
        }
    }

    /**
     * 邀请人展示名：昵称优先，其次用户名；用户服务不可用时降级为通用称呼，
     * 不阻断邀请（站内信本身只需要 userId 就能落库）。
     */
    private String resolveInviterName(Long inviterId) {
        try {
            Result<UserDto> body = userFeignClient.getUserInfo(inviterId);
            if (body != null && body.getData() != null) {
                UserDto user = body.getData();
                if (user.getNickName() != null && !user.getNickName().isBlank()) {
                    return user.getNickName();
                }
                if (user.getUserName() != null && !user.getUserName().isBlank()) {
                    return user.getUserName();
                }
            }
        } catch (Exception e) {
            log.warn("获取邀请人昵称失败，使用通用称呼: inviterId={}", inviterId, e);
        }
        return "小组管理员";
    }
}
