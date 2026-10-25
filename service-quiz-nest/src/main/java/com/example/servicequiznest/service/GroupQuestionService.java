package com.example.servicequiznest.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.servicequiznest.dto.CreateGroupQuestionDto;
import com.example.servicequiznest.dto.UpdateGroupQuestionDto;
import com.example.servicequiznest.entity.GroupMember;
import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.enums.GroupJoinStatus;
import com.example.servicequiznest.enums.GroupMemberRole;
import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import com.example.servicequiznest.util.check.CheckGroupQuestion;
import com.example.servicequiznest.util.convert.GroupQuestionConvert;
import com.example.servicequiznest.vo.GroupQuestionVo;
import com.example.servicequiznest.vo.MinimalQuestionVo;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.CursorPageResult;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionBriefDto;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /*
     * service-question 的 QuestionErrorCode 中与改题链路相关的错误码。
     * quiz-nest 不依赖 service-question 模块，无法直接引用其枚举，因此在这里登记后翻译为
     * 本服务文案；新增映射时必须同步 service-question/QuestionErrorCode。
     */
    /** 仅小组自建题目支持修改（4030203）。 */
    private static final int QUESTION_NOT_EDITABLE_CODE = 4030203;
    /** 题目不存在或已被删除（4040201）。 */
    private static final int QUESTION_NOT_FOUND_CODE = 4040201;
    /** 题目正文与已有题目重复（4090208）。 */
    private static final int QUESTION_CONTENT_DUPLICATED_CODE = 4090208;

    public GroupQuestionService(
            QuestionFeignClient questionFeignClient,
            GroupMemberMapper groupMemberMapper,
            GroupQuestionMapper groupQuestionMapper,
            GroupQuestionRollbackPublisher rollbackPublisher,
            GroupQuestionCleanupPublisher cleanupPublisher,
            @Value("${codewise.internal-token}") String internalToken
            ) {
        this.groupMemberMapper = groupMemberMapper;
        this.groupQuestionMapper = groupQuestionMapper;
        this.rollbackPublisher = rollbackPublisher;
        this.cleanupPublisher = cleanupPublisher;
        this.internalToken = internalToken;
        this.questionFeignClient = questionFeignClient;
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
        CheckGroupQuestion.checkCreate(dto);
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
            body = questionFeignClient.addQuestion(internalToken,
                    GroupQuestionConvert.createGroupQuestionDtoToQuestionDto(dto));
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
     * 平台题目就不需要同步添加题目了，只需要在此直接为小组题库加入索引即可。
     *
     * <p>题目服务按 id 返回题目时不区分可见性，因此必须在写索引前自行校验：
     * 公开题任何人可加，私密题仅创建人可加，下架/审核中/状态缺失一律拒绝，
     * 避免把他人私密题或违规题扩散到小组。不可见统一按「题目不存在」返回，
     * 不向调用方泄露题目是否存在。</p>
     */
    private void addPlatFromQuestion(CreateGroupQuestionDto dto){

       Result< QuestionDto > body=questionFeignClient.getQuestionInfo(dto.getQuestionId());
        if(body==null||body.getData()==null){
            throw new BizException(QuizNestErrorCode.NOTFOUND_QUESTION);
        }
        if(!canBeAddedToGroup(body.getData())){
            log.warn("拒绝将不可见题目加入小组: groupId={}, questionId={}, userId={}",
                    dto.getGroupId(), dto.getQuestionId(), UserContext.getUserId());
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
     * 平台题目加入小组的可见性规则。
     *
     * <ul>
     *   <li>{@code status=1}（正常公开）：任何人可加入小组；</li>
     *   <li>{@code status=3}（私密）：仅创建人本人可加入（自己的私密题可进自己的小组）；</li>
     *   <li>{@code status=0}（下架）/ {@code 2}（审核中）/ 缺失：一律不可加入。</li>
     * </ul>
     */
    private boolean canBeAddedToGroup(QuestionDto question){
        if(question.getQuestionId()==null){
            return false;
        }
        Integer status = question.getStatus();
        if(Integer.valueOf(1).equals(status)){
            return true;
        }
        return Integer.valueOf(3).equals(status)
                && Objects.equals(question.getCreateUserId(), UserContext.getUserId());
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

    /**
     * 删除小组题目，在本方法中需要判断该题目是否为平台题目，再选择处理方法。
     * @param groupQuestionId 小组题目记录表主键
     * @param groupId 小组id
     */
    public void deleteGroupQuestion(Long groupQuestionId,Long groupId){
        if(groupId==null)throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        if(groupId<=0)throw new BizException(QuizNestErrorCode.INVALID_GROUP_ID);
        if(groupQuestionId==null||groupQuestionId<=0)throw new BizException(QuizNestErrorCode.INVALID_QUESTION_ID);
        checkMemberRole(UserContext.getUserId(),groupId);

        GroupQuestion groupQuestion=groupQuestionMapper.selectById(groupQuestionId);
        if(groupQuestion==null)throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);
        // 索引必须属于当前小组：否则 A 组管理员可凭 B 组的 groupQuestionId 跨组删除
        if(!Objects.equals(groupQuestion.getGroupId(),groupId)){
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);
        }
        //该题目为平台题目，只删小组索引，题目本体保留在题库中
        if(Integer.valueOf(1).equals(groupQuestion.getIsPlatFromQuestion())){
            int r=groupQuestionMapper.deleteById(groupQuestionId);
            if(r==0)throw new BizException(QuizNestErrorCode.DELETE_GROUP_QUESTION_FAILED);
            return;
        }

        /*执行到此说明为自建题目，调用deletePrivateQuestion,以删除题目，异常透穿即可，详情可看deletePrivateQuestion注释*/
        deletePrivateQuestion(groupQuestion.getQuestionId(),groupQuestionId);


    }

    /**
     * 删除小组私有题目，在此依旧选择同步调用题目服务接口
     * <B>删除方法不做强一致，当题目服务返回成功才删除该记录，假如题目服务删除成功但是接口超时在此不做回调
     * ，采用懒删除，下次查看该题目时假如题目服务不存在该题目再做删除</B>
     * @param questionId 需要删除的题目id
     * @param groupQuestionId 小组题目索引表主键
     */
    private void deletePrivateQuestion(Long questionId ,Long groupQuestionId){
        try {
            Result<String> body=questionFeignClient.deleteQuestion(internalToken,questionId);
            if(body==null||body.getCode()!=200||body.getMessage()==null||!"success".equals(body.getMessage())){
                throw new BizException(QuizNestErrorCode.DELETE_GROUP_QUESTION_FAILED);
            }
        }catch (Exception e){
            log.error("小组题目删除失败，id为{}",questionId,e);
            throw new BizException(QuizNestErrorCode.DELETE_GROUP_QUESTION_FAILED);
        }

        //执行到此证明题库已经删除成功，尝试删除题目索引
        //如果删除失败，用户重试后发现题库不存在这一题，也会进行到此，也就是会二次尝试，如果用户没有重试就留到下次有用户查看时懒删除
        int r= groupQuestionMapper.deleteById(groupQuestionId);
        if(r==0){
            throw new BizException(QuizNestErrorCode.DELETE_GROUP_QUESTION_FAILED);
        }


    }


    /**
     * 游标查询获取该小组的题目列表，返回轻量的题目对象列表。
     *
     * <p>游标是 {@code group_question.group_question_id}（自增、稳定），按升序翻页；
     * 每次多查一条判断 {@code hasNext}，返回给前端的 {@code nextCursor} 始终是
     * 本页最后一条<b>已消费</b>索引的主键，因此即使本页有题目已被题目服务删除，
     * 下一次请求也不会重复或跳过数据。</p>
     *
     * <p>题目本体通过 {@link QuestionFeignClient#getFavoritesBrief(List)} 批量获取，
     * 不逐题调用，避免 N+1。小组题目本身是私密的，索引存在即对组员可见，
     * 不再按题目 status 过滤；题目服务已不存在的题目按注释约定做懒删除
     * （删索引失败只记日志，不影响本次查询结果）。</p>
     *
     * @param groupId  小组 id
     * @param lastId   上一页返回的游标，为空或非正数表示第一页
     * @param pageSize 每页条数，为空/非正数取默认值，超过上限按上限截断
     * @return {@link CursorPageResult} 返回公共依赖的自定义分页返回体
     */
    public CursorPageResult<MinimalQuestionVo> cursorQuestion(Long groupId, Long lastId, Integer pageSize){
        if(groupId==null)throw new BizException(QuizNestErrorCode.GROUP_ID_REQUIRED);
        if(groupId<=0)throw new BizException(QuizNestErrorCode.INVALID_GROUP_ID);
        checkApprovedMember(UserContext.getUserId(), groupId);

        int limit = normalizePageSize(pageSize);
        QueryWrapper<GroupQuestion> wrapper = new QueryWrapper<GroupQuestion>()
                .eq("group_id", groupId)
                .orderByAsc("group_question_id")
                .last("LIMIT " + (limit + 1));
        if(lastId!=null&&lastId>0){
            wrapper.gt("group_question_id", lastId);
        }

        List<GroupQuestion> rows = groupQuestionMapper.selectList(wrapper);
        boolean hasNext = rows.size() > limit;
        List<GroupQuestion> pageRows = hasNext
                ? new ArrayList<>(rows.subList(0, limit))
                : rows;
        if(pageRows.isEmpty()){
            return CursorPageResult.<MinimalQuestionVo>builder()
                    .records(Collections.emptyList())
                    .nextCursor(null)
                    .hasNext(false)
                    .build();
        }
        // 游标取「最后一条已消费索引」而不是「最后一条返回记录」：被懒删除的题目不会让游标停滞
        Long nextCursor = hasNext ? pageRows.getLast().getGroupQuestionId() : null;

        List<Long> questionIds = pageRows.stream()
                .map(GroupQuestion::getQuestionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, QuestionBriefDto> briefMap = loadQuestionBriefs(questionIds);

        // 题目服务已不存在的题目：懒删除本地索引（删组/删题超时留下的孤儿数据）
        List<Long> staleMappingIds = pageRows.stream()
                .filter(row -> row.getQuestionId()==null||!briefMap.containsKey(row.getQuestionId()))
                .map(GroupQuestion::getGroupQuestionId)
                .filter(Objects::nonNull)
                .toList();
        if(!staleMappingIds.isEmpty()){
            lazyDeleteStaleMappings(staleMappingIds, groupId);
        }

        List<MinimalQuestionVo> records = pageRows.stream()
                .filter(row -> row.getQuestionId()!=null&&briefMap.containsKey(row.getQuestionId()))
                .map(row -> new MinimalQuestionVo(briefMap.get(row.getQuestionId()), row.getGroupQuestionId()))
                .toList();

        return CursorPageResult.<MinimalQuestionVo>builder()
                .records(records)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    /**
     * 浏览鉴权：只要求「已加入」（APPROVED），不要求管理员。
     *
     * <p>不能复用 {@link #checkMemberRole}——那是添加题目的管理员校验，
     * 会把普通成员挡在外面；同时必须校验 {@code join_status}，
     * 否则 PENDING/REJECTED 占位记录也能看到小组题库。</p>
     */
    private void checkApprovedMember(Long userId, Long groupId){
        GroupMember groupMember = groupMemberMapper.selectOne(new QueryWrapper<GroupMember>()
                .eq("user_id", userId)
                .eq("group_id", groupId));
        if(groupMember==null||!GroupJoinStatus.APPROVED.equals(groupMember.getJoinStatus())){
            throw new BizException(QuizNestErrorCode.NOTFOUND_USER_IN_GROUP);
        }
    }

    /** 每页默认条数与上限，避免调用方传超大 pageSize 打穿题目服务。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private int normalizePageSize(Integer pageSize){
        if(pageSize==null||pageSize<=0){
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 批量获取题目瘦身信息并建立 questionId 索引。
     *
     * <p>题目服务不可用必须显式失败：返回空列表会被误判成「题目都被删了」，
     * 进而把小组题库索引全部懒删除。</p>
     */
    private Map<Long, QuestionBriefDto> loadQuestionBriefs(List<Long> questionIds){
        if(questionIds.isEmpty()){
            return Collections.emptyMap();
        }
        Result<List<QuestionBriefDto>> body;
        try {
            body = questionFeignClient.getFavoritesBrief(questionIds);
        } catch (Exception e) {
            log.error("批量查询小组题目瘦身信息失败: questionIds={}", questionIds, e);
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, e);
        }
        if(body==null||body.getCode()==null||body.getCode()!=200||body.getData()==null){
            log.error("批量查询小组题目瘦身信息返回异常: questionIds={}, body={}", questionIds, body);
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        Map<Long, QuestionBriefDto> briefMap = new HashMap<>(body.getData().size());
        for (QuestionBriefDto brief : body.getData()) {
            if(brief!=null&&brief.getQuestionId()!=null){
                briefMap.put(brief.getQuestionId(), brief);
            }
        }
        return briefMap;
    }

    /** 懒删除失败不能影响列表返回：索引残留会在下一次查询时再次尝试清理。 */
    private void lazyDeleteStaleMappings(List<Long> mappingIds, Long groupId){
        try {
            groupQuestionMapper.deleteByIds(mappingIds);
            log.info("小组题库懒删除已失效题目索引: groupId={}, mappingIds={}", groupId, mappingIds);
        } catch (Exception e) {
            log.warn("小组题库懒删除失败，保留索引待下次清理: groupId={}, mappingIds={}", groupId, mappingIds, e);
        }
    }

    /**
     * 获取小组题目的具体内容，是本小组的人即可查看。
     *
     * <p>索引必须属于路径上的小组，否则 A 组成员可以拿 B 组的索引 id 读到 B 组题目。
     * 题目服务返回非 200 时可能是超时，不能据此删除本地索引；只有明确返回
     * {@code data=null} 才说明题目已不存在，此时做懒删除。</p>
     *
     * @param groupQuestionId 小组题目索引 id
     * @param groupId         小组 id
     * @return {@link GroupQuestionVo} 包含题目详情与小组索引 id
     */
    public GroupQuestionVo getQuestion(Long groupQuestionId,Long groupId){
        checkGroupQuestionIds(groupQuestionId, groupId);
        checkApprovedMember(UserContext.getUserId(),groupId);
        GroupQuestion groupQuestion=groupQuestionMapper.selectById(groupQuestionId);
        if(groupQuestion==null||!Objects.equals(groupQuestion.getGroupId(),groupId)){
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);
        }
        Result<QuestionDto>body=questionFeignClient.getQuestionInfo(groupQuestion.getQuestionId());
        //code返回码不等于200,证明异常可能是接口超时此时不能删除本地索引，因为我们没确定是超时还是不存在，当code==200,且data==null，则证明不存在该题目。
        if(body==null||body.getCode()==null||body.getCode()!=200){
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if(body.getData()==null){
            lazyDeleteStaleMappings(List.of(groupQuestionId),groupId);
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);
        }

        return new GroupQuestionVo(body.getData(),groupQuestionId);
    }

    /**
     * 修改小组题目，只有本组 ROOT/ADMIN 可以操作。
     *
     * <p>修改对象由路径上的小组索引决定，请求体里的 questionId 不参与定位；
     * 平台题目只在小组题库中保留索引，正文不属于小组资产，因此不允许在组内修改。</p>
     *
     * @param updateGroupQuestionDto 修改内容（全量）
     * @param groupQuestionId        小组题目索引 id
     * @param groupId                小组 id
     */
    public void updateGroupQuestion(UpdateGroupQuestionDto updateGroupQuestionDto, Long groupQuestionId,Long groupId){
        checkGroupQuestionIds(groupQuestionId, groupId);
        CheckGroupQuestion.checkUpdate(updateGroupQuestionDto);

        GroupQuestion groupQuestion=groupQuestionMapper.selectById(groupQuestionId);
        if(groupQuestion==null||!Objects.equals(groupQuestion.getGroupId(),groupId)){
            throw new BizException(QuizNestErrorCode.NOTFOUND_GROUP_QUESTION);
        }
        if(!Integer.valueOf(0).equals(groupQuestion.getIsPlatFromQuestion())){
            throw new BizException(QuizNestErrorCode.NOT_ALLOWED_UPDATE_PLATFORM_QUESTION);
        }
        checkMemberRole(UserContext.getUserId(),groupId);
        try {
            Result<QuestionDto> body = questionFeignClient.putQuestion(
                    internalToken,
                    GroupQuestionConvert.quizUpdateGroupQuestionDtoToApiUpdateGroupQuestionDto(
                            updateGroupQuestionDto, groupQuestion.getQuestionId()));
            checkUpdateQuestionResult(body);
        }catch (BizException e){
            throw e;
        }catch (Exception e){
            log.error("修改组内题目接口调用失败，小组题目id{},题目id{}",groupQuestionId,groupQuestion.getQuestionId(),e);
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
    }

    /** 校验小组题目两个路径参数：空值按缺失处理，非正数按非法 id 处理。 */
    private void checkGroupQuestionIds(Long groupQuestionId, Long groupId){
        if(groupId==null||groupQuestionId==null){
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        if(groupId<=0){
            throw new BizException(QuizNestErrorCode.INVALID_GROUP_ID);
        }
        if(groupQuestionId<=0){
            throw new BizException(QuizNestErrorCode.INVALID_QUESTION_ID);
        }
    }

    /**
     * 把 service-question 内部改题接口的业务错误翻译成本服务的错误码。
     *
     * <p>下游返回的 code 来自 {@code QuestionErrorCode}，这里只识别与改题链路
     * 相关的三种语义，其余一律按下游不可用处理，避免把陌生错误码透传给前端。</p>
     */
    private void checkUpdateQuestionResult(Result<QuestionDto> body){
        if(body==null||body.getCode()==null){
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        int code = body.getCode();
        if(code==200){
            return;
        }
        if(code==QUESTION_NOT_EDITABLE_CODE){
            throw new BizException(QuizNestErrorCode.NOT_ALLOWED_UPDATE_PLATFORM_QUESTION);
        }
        if(code==QUESTION_NOT_FOUND_CODE){
            throw new BizException(QuizNestErrorCode.NOTFOUND_QUESTION);
        }
        if(code==QUESTION_CONTENT_DUPLICATED_CODE){
            throw new BizException(QuizNestErrorCode.QUESTION_CONTENT_DUPLICATED);
        }
        log.error("修改组内题目下游返回异常: code={}, message={}", code, body.getMessage());
        throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
    }
}
