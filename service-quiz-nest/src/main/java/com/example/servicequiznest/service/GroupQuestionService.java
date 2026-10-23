package com.example.servicequiznest.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class GroupQuestionService {

    private final QuestionFeignClient questionFeignClient;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupQuestionMapper groupQuestionMapper;
    private final GroupQuestionRollbackPublisher rollbackPublisher;
    private final GroupQuestionCleanupPublisher cleanupPublisher;
    /** 调用题目服务内部建题接口的凭证（网关不注入该头，外部无法伪造）。 */
    private final String internalToken;

    public GroupQuestionService(
            QuestionFeignClient questionFeignClient,
            GroupMemberMapper groupMemberMapper,
            GroupQuestionMapper groupQuestionMapper,
            GroupQuestionRollbackPublisher rollbackPublisher,
            GroupQuestionCleanupPublisher cleanupPublisher,
            @Value("${codewise.internal-token}") String internalToken) {
        this.groupMemberMapper = groupMemberMapper;
        this.questionFeignClient = questionFeignClient;
        this.groupQuestionMapper = groupQuestionMapper;
        this.rollbackPublisher = rollbackPublisher;
        this.cleanupPublisher = cleanupPublisher;
        this.internalToken = internalToken;
    }

    /**
     * 注意添加题目不允许异步使用mq，也就是需要同步调用添加接口
     * <B>原因如下：如果使用mq，那么我们并不知道题目是否真的添加成功，那么后续需要回调或者失败后通过站内信来通知用户
     * 且假如记录入库成功但是题库入库失败可能会小组题库有但是实际没有这种情况</B>
     * <B>如果采用同步，那么如果接口返回异常该接口透穿异常亦可以同步回滚</B>
     *
     * <p>自建题目在题目服务建题成功、本地 {@code group_question} 写入失败时，
     * 通过 OutboxPro 发布 {@code QUESTION_CREATE_ROLLBACK}，由题目服务删除刚创建的题目。</p>
     *
     * @param dto 添加题目所用dto，如果{@code questionId}不为空则认为是平台题目，届时不再使用其他字段
     * @throws org.example.serviceapi.exception.BizException 抛出自定义异常
     */
    public void addQuestion(CreateGroupQuestionDto dto){
        if(dto==null)throw new BizException(QuizNestErrorCode.QUESTION_REQUIRED);
        checkQuestionDto(dto);
        checkMemberRole(UserContext.getUserId(),dto.getGroupId());
        if(dto.getQuestionId()!=null){
            addPlatFromQuestion(dto);
            return;
        }
        addCustomQuestion(dto);
    }

    /**
     * 自建题目：同步调用题目服务建题，成功后再写本地小组索引。
     *
     * <p>题目服务已提交而本地写入失败时，题目不能留在题库里（小组侧没有入口），
     * 因此发布回滚事件让题目服务删除；回滚事件写 outbox 失败只记 ERROR（带 questionId），
     * 由人工按日志清理，且不掩盖原始失败。</p>
     */
    private void addCustomQuestion(CreateGroupQuestionDto dto){
        Result<QuestionDto> body;
        try {
            body = questionFeignClient.addQuestion(internalToken, createGroupQuestionDtoToQuestionDto(dto));
        } catch (Exception e) {
            // 题目服务自身事务已回滚，本地没有需要补偿的数据
            log.error("同步创建小组题目失败: groupId={}", dto.getGroupId(), e);
            throw new BizException(QuizNestErrorCode.ADD_QUESTION_FAILED, e);
        }
        if(body==null||body.getData()==null||body.getData().getQuestionId()==null){
            throw new BizException(QuizNestErrorCode.ADD_QUESTION_FAILED);
        }

        Long questionId = body.getData().getQuestionId();
        try {
            if(addGroupQuestion(questionId, dto)>0){
                return;
            }
        } catch (Exception e) {
            publishRollbackQuietly(questionId, dto.getGroupId());
            throw new BizException(QuizNestErrorCode.ADD_QUESTION_FAILED, e);
        }
        publishRollbackQuietly(questionId, dto.getGroupId());
        throw new BizException(QuizNestErrorCode.ADD_QUESTION_FAILED);
    }

    /**
     * 自建题目写入小组索引（isPlatFromQuestion=0），返回影响行数供调用方决定是否补偿。
     */
    private int addGroupQuestion(Long questionId,CreateGroupQuestionDto dto){
        GroupQuestion groupQuestion = GroupQuestion.builder()
                .questionId(questionId)
                .createUserId(UserContext.getUserId())
                .groupId(dto.getGroupId())
                .isPlatFromQuestion(0)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now()).build();
        return groupQuestionMapper.insert(groupQuestion);
    }

    /** 回滚事件发布失败不能覆盖「本地写入失败」这个原始结果，只记录待人工清理的题目 id。 */
    private void publishRollbackQuietly(Long questionId,Long groupId){
        try {
            rollbackPublisher.publish(questionId, groupId);
            log.warn("小组题目本地关联写入失败，已发布回滚事件: questionId={}, groupId={}", questionId, groupId);
        } catch (Exception e) {
            log.error("小组题目本地关联写入失败，且回滚事件写入 outbox 失败，需人工删除题目: questionId={}, groupId={}",
                    questionId, groupId, e);
        }
    }

    /**
     * 删组时清理小组题库：删除该组全部索引，并把自建题 ID 交给题目服务删除。
     *
     * <p>必须在删组事务内调用（MANDATORY）：本地索引删除、清理事件与小组删除同事务，
     * 任一步失败整体回滚，不会出现「组还在、题被删」。平台题目只删索引，题目本体保留。</p>
     *
     * @param groupId 正在删除的小组 id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteGroupQuestions(Long groupId) {
        if (groupId == null) {
            return;
        }
        List<GroupQuestion> mappings = groupQuestionMapper.selectList(
                new QueryWrapper<GroupQuestion>().eq("group_id", groupId));
        if (mappings.isEmpty()) {
            return;
        }
        List<Long> customQuestionIds = mappings.stream()
                .filter(mapping -> Integer.valueOf(0).equals(mapping.getIsPlatFromQuestion()))
                .map(GroupQuestion::getQuestionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        groupQuestionMapper.delete(new QueryWrapper<GroupQuestion>().eq("group_id", groupId));
        if (!customQuestionIds.isEmpty()) {
            cleanupPublisher.publish(groupId, customQuestionIds);
        }
        log.info("小组题库清理完成: groupId={}, total={}, custom={}",
                groupId, mappings.size(), customQuestionIds.size());
    }

    /**
     * 自建题目 DTO 转题目服务入参。
     *
     * <p>只映射出题字段；questionId/status/createUserId 等由题目服务生成或控制，
     * 不能由调用方指定。</p>
     */
    private QuestionDto createGroupQuestionDtoToQuestionDto(CreateGroupQuestionDto dto){
        return QuestionDto.builder()
                .title(dto.getTitle())
                .description(dto.getDescription())
                .inputDesc(dto.getInputDesc())
                .outputDesc(dto.getOutputDesc())
                .sampleInput(dto.getSampleInput())
                .sampleOutput(dto.getSampleOutput())
                .hint(dto.getHint())
                .tags(dto.getTags())
                .timeLimit(dto.getTimeLimit())
                .memoryLimit(dto.getMemoryLimit())
                .build();
    }

    /**
     * 平台题目就不需要同步添加题目了，只需要在此直接为小组题库加入索引即可
     */
    private void addPlatFromQuestion(CreateGroupQuestionDto dto){

       Result< QuestionDto > body=questionFeignClient.getQuestionInfo(dto.getQuestionId());
        if(body==null||body.getData()==null){
            throw new BizException(QuizNestErrorCode.NOTFOUND_QUESTION);
        }

        GroupQuestion groupQuestion = GroupQuestion.builder()
                .questionId(dto.getQuestionId())
                .createUserId(UserContext.getUserId())
                .groupId(dto.getGroupId())
                .isPlatFromQuestion(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now()).build();


        try {
            int r=groupQuestionMapper.insert(groupQuestion);
            if(r==0)throw new BizException(QuizNestErrorCode.ADD_QUESTION_FAILED);
        } catch (DuplicateKeyException e) {
            // uk_group_question_group_question：同一道题重复加入同一个小组
            throw new BizException(QuizNestErrorCode.QUESTION_ALREADY_IN_GROUP, e);
        }
    }

    /**
     * 校验新增题目入参。
     *
     * <p>平台题目（{@code questionId} 非空）只校验 id，其余字段按约定忽略，
     * 不能因为调用方没传标题就拒绝；小组自建题目则校验必填项与长度上限，
     * 上限与 service-question 的 {@code question} 表保持一致，避免同步建题时
     * 才由下游报错。</p>
     *
     * @param dto 已验证非空的入参
     */
    private void checkQuestionDto(CreateGroupQuestionDto dto){
        if(dto.getGroupId()==null){
            throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        }
        if(dto.getQuestionId()!=null){
            //平台题目只引用已有题目，title/description 等字段不参与入库，不能要求必填
            if(dto.getQuestionId()<=0){
                throw new BizException(CommonErrorCode.PARAM_INVALID);
            }
            return;
        }
        if(!StringUtils.hasText(dto.getTitle())){
            throw new BizException(QuizNestErrorCode.QUESTION_TITLE_REQUIRED);
        }
        if(!StringUtils.hasText(dto.getDescription())){
            throw new BizException(QuizNestErrorCode.QUESTION_CONTENT_REQUIRED);
        }
        if(dto.getTitle().codePointCount(0,dto.getTitle().length())>200){
            throw new BizException(QuizNestErrorCode.QUESTION_TITLE_TOO_LONG,200);
        }
        if(dto.getTags()!=null&&dto.getTags().codePointCount(0,dto.getTags().length())>255){
            throw new BizException(QuizNestErrorCode.QUESTION_TAGS_TOO_LONG,255);
        }
        validateTextLength(dto.getInputDesc(),"输入描述");
        validateTextLength(dto.getOutputDesc(),"输出描述");
        validateTextLength(dto.getSampleInput(),"样例输入");
        validateTextLength(dto.getSampleOutput(),"样例输出");
        validateTextLength(dto.getHint(),"提示");
        if(dto.getTimeLimit()==null||dto.getTimeLimit()<=0){
            throw new BizException(QuizNestErrorCode.QUESTION_TIME_LIMIT_INVALID);
        }
        if(dto.getMemoryLimit()==null||dto.getMemoryLimit()<=0){
            throw new BizException(QuizNestErrorCode.QUESTION_MEMORY_LIMIT_INVALID);
        }
    }

    /** TEXT 列按 UTF-8 字节数限制，中文/emoji 不能按 String.length() 判断。 */
    private void validateTextLength(String value,String fieldName){
        if(value!=null&&value.getBytes(StandardCharsets.UTF_8).length>65_535){
            throw new BizException(QuizNestErrorCode.QUESTION_TEXT_TOO_LONG,fieldName);
        }
    }

    /**
     * 判断该成员是否属于小组且是否为管理员及超级管理
     * @throws BizException 抛出自定义异常，无须捕获，透穿给用户即可
     */
    private void checkMemberRole(Long userId,Long groupId){

        GroupMember groupMember=groupMemberMapper.selectOne(
                new QueryWrapper<GroupMember>().eq("user_id",userId).
                        eq("group_id",groupId)
        );
        if(groupMember==null)throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        if(GroupMemberRole.MEMBER.equals(groupMember.getRole())){
            throw new BizException(QuizNestErrorCode.NO_PERMISSION_ADD_QUESTION);
        }
    }



}
