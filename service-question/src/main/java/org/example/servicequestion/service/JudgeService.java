package org.example.servicequestion.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.GroupSubmitRecord;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.GroupSubmitRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
public class JudgeService {
    @Autowired
    private SubmitRecordMapper submitRecordMapper;

    @Autowired
    private OutboxProPublisher outboxPublisher;

    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private GroupQuestionPermissionService groupQuestionPermissionService;
    @Autowired
    private GroupSubmitRecordMapper groupSubmitRecordMapper;

    /**
     * 普通提交入口：小组提交时 {@link GetCodeDto#getGroupId()} 会由
     * {@link #judge(GetCodeDto, Long)} 继续处理。
     */
    public Long judge(GetCodeDto getCodeDto) {
        return judge(getCodeDto, getCodeDto == null ? null : getCodeDto.getGroupId());
    }

    /**
     * 提交判题唯一主流程。
     *
     * <p>提交场景先于查库校验：非法场景不产生任何 DB/Feign 调用。小组提交
     * （{@code submitScene=GROUP}）必须携带 groupId，且无论题目是否公开，
     * 都要通过「题目在组内 + 用户是组员」校验。</p>
     *
     * @param getCodeDto 提交参数
     * @param groupId    小组 id；普通提交传 null
     * @return 提交记录 id
     */
    public Long judge(GetCodeDto getCodeDto, Long groupId) {
        if (getCodeDto == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        String submitScene = getType(getCodeDto.getSubmitScene());
        assertGroupSceneConsistent(submitScene, groupId);

        Long questionId = getCodeDto.getQuestionId();
        if (questionId == null) {
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }
        Question question = loadQuestion(questionId);
        assertSubmittable(question, groupId);

        SubmitRecord submitRecord = buildSubmit(getCodeDto, submitScene);
        return executeInTransaction(() -> addSubmitAndSend(submitRecord, groupId));
    }

    /**
     * 校验小组场景与 groupId 是否匹配，避免「传了 groupId 却按普通提交落库」。
     */
    private void assertGroupSceneConsistent(String submitScene, Long groupId) {
        if ("GROUP".equals(submitScene)) {
            if (groupId == null) {
                throw new BizException(QuestionErrorCode.GROUP_ID_REQUIRED);
            }
            if (groupId <= 0) {
                throw new BizException(QuestionErrorCode.GROUP_ID_INVALID);
            }
            return;
        }
        if (groupId != null) {
            throw new BizException(QuestionErrorCode.GROUP_SUBMIT_SCENE_REQUIRED);
        }
    }

    /**
     * 校验当前用户是否可以提交该题目。
     *
     * <p>普通提交：公开题直接放行；私密题（status=3）交给
     * {@link GroupQuestionPermissionService} 判断创建者/组员/管理员身份。
     * 小组提交：先校验小组归属与成员身份（公开题也不例外），再校验题目状态；
     * 普通提交不可见时统一返回「资源不存在」，避免泄露题目是否存在。</p>
     *
     * @param question 已加载的题目
     * @param groupId  小组 id；普通提交传 null
     */
    private void assertSubmittable(Question question, Long groupId) {
        if (groupId != null) {
            groupQuestionPermissionService.assertQuestionInGroupAndIsGroupMember(
                    question.getQuestionId(), groupId);
            assertQuestionStatusForSubmit(question);
            return;
        }
        Integer status = question.getStatus();
        if (Integer.valueOf(1).equals(status)) {
            return;
        }
        if (Integer.valueOf(3).equals(status)
                && groupQuestionPermissionService.canAccessPrivateQuestion(question)) {
            return;
        }
        throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 题目状态是否允许提交：下架/审核中给出明确业务错误。 */
    private void assertQuestionStatusForSubmit(Question question) {
        Integer status = question.getStatus();
        if (Integer.valueOf(1).equals(status) || Integer.valueOf(3).equals(status)) {
            return;
        }
        if (Integer.valueOf(0).equals(status)) {
            throw new BizException(QuestionErrorCode.QUESTION_OFF_SHELF);
        }
        if (Integer.valueOf(2).equals(status)) {
            throw new BizException(QuestionErrorCode.QUESTION_UNDER_REVIEW);
        }
        throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    private Question loadQuestion(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }
        return question;
    }

    /**
     * 同事务内写提交记录（小组提交额外写小组索引）并登记判题 Outbox 事件。
     *
     * <p>任何一步失败都抛异常，由 {@link #executeInTransaction(Supplier)} 统一回滚，
     * 不会出现「submit_record 已提交但 Outbox 未登记」或「小组索引缺失」。</p>
     */
    private Long addSubmitAndSend(SubmitRecord submitRecord, Long groupId) {
        int inserted = submitRecordMapper.insert(submitRecord);
        if (inserted == 0 || submitRecord.getSubmitRecordId() == null) {
            throw new BizException(QuestionErrorCode.SUBMIT_RECORD_SAVE_FAILED);
        }
        if (groupId != null) {
            GroupSubmitRecord groupSubmitRecord = GroupSubmitRecord.builder()
                    .submitRecordId(submitRecord.getSubmitRecordId())
                    .userId(submitRecord.getUserId())
                    .groupId(groupId)
                    .createTime(LocalDateTime.now())
                    .build();
            int indexed = groupSubmitRecordMapper.insert(groupSubmitRecord);
            if (indexed == 0) {
                throw new BizException(QuestionErrorCode.GROUP_SUBMIT_RECORD_SAVE_FAILED);
            }
        }
        // 判题请求走事务性 Outbox：payload 只放 submitRecordId（小字段引用），
        // 路由（judge.exchange/judge.routing）在 OutboxEventRouteConfig 登记
        outboxPublisher.publish(EventTypes.JUDGE_SUBMIT_REQUEST, submitRecord.getSubmitRecordId());
        return submitRecord.getSubmitRecordId();
    }

    /**
     * 统一的事务模板：业务异常原样透传，其余异常回滚并映射为判题服务不可用，
     * 回调返回 null 视为提交失败。
     */
    private Long executeInTransaction(Supplier<Long> action) {
        try {
            Long submitRecordId = transactionTemplate.execute(status -> {
                try {
                    return action.get();
                } catch (RuntimeException e) {
                    status.setRollbackOnly();
                    throw e;
                }
            });
            if (submitRecordId == null) {
                throw new BizException(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE);
            }
            return submitRecordId;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("判题提交失败", e);
            throw new BizException(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE, e);
        }
    }

    private SubmitRecord buildSubmit(GetCodeDto getCodeDto, String submitScene) {
        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitContent(getCodeDto.getCode());
        submitRecord.setLanguage(getCodeDto.getLanguage());
        submitRecord.setQuestionTitle(getCodeDto.getQuestionTitle());
        submitRecord.setSubmitTime(LocalDateTime.now());
        submitRecord.setJudgeStatus("pending"); //pending/failue/success
        submitRecord.setQuestionId(getCodeDto.getQuestionId());
        submitRecord.setUserId(UserContext.getUserId());
        submitRecord.setSubmitScene(submitScene);
        return submitRecord;
    }

    /**
     * 归一化提交场景。
     *
     * @param type 客户端提交的 submitScene（NORMAL/REVIEW/PLAN/GROUP）
     * @return 归一化后的场景字符串
     * @throws BizException 场景缺失（SUBMIT_SCENE_REQUIRED）或不在枚举内
     *         （SUBMIT_SCENE_UNSUPPORTED）——缺失时必须返回明确业务错误，
     *         不能进入 switch 触发 NPE（否则客户端漏传字段拿到 500 而非 400 语义）
     */
    private String getType(String type) {
        if (type == null || type.isBlank()) {
            throw new BizException(QuestionErrorCode.SUBMIT_SCENE_REQUIRED);
        }
        return switch (type) {
            case "REVIEW" -> "REVIEW";
            case "NORMAL" -> "NORMAL";
            case "PLAN" -> "PLAN";
            case "GROUP" -> "GROUP";
            default -> throw new BizException(QuestionErrorCode.SUBMIT_SCENE_UNSUPPORTED);
        };
    }

    /**
     * 调试判题：Redis 任务写入先行，Outbox 事件随后登记。
     *
     * <p>Redis 写与 Outbox 登记不属于同一个数据库事务，不能声明二者原子；
     * 这里保持「任务先落 Redis、事件后登记」的顺序，事件投递失败时由调试任务
     * 自身的过期机制兜底。</p>
     */
    public String debug(DebugDto debugDto) {
        if (debugDto == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        if (debugDto.getQuestionId() == null) {
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }
        Question question = loadQuestion(debugDto.getQuestionId());
        assertSubmittable(question, null);

        String uuid = UUID.randomUUID().toString();
        debugDto.setUserId(UserContext.getUserId());
        redisTemplate.opsForHash().put(RedisContext.JUDGE_DEBUG_KEY, uuid, debugDto);
        outboxPublisher.publish(EventTypes.JUDGE_DEBUG_REQUEST, uuid);
        return uuid;
    }
}
