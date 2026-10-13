package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.dto.InsertQuestionDto;
import org.example.servicequestion.dto.ReturnQuestionDto;
import org.example.servicequestion.entry.FunctionConfig;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.enums.QuestionType;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.example.servicequestion.vo.AgentQuestionDetailVo;
import org.example.servicequestion.vo.QuestionVo;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class QuestionService {
    private final String LOCK_ADD_QUESTION = "lock_add_question";
    @Autowired
    private QuestionMapper questionMapper;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private SubmitRecordMapper submitRecordMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private UserFeignClient userFeignClient;
    @Autowired
    private FunctionConfigMapper  functionConfigMapper;


    public Long getTotalQuestionCount() {
        Long total = 0L;
        // Redis 经 Jackson 序列化：无类型信息时小数值会反序列化成 Integer，
        // 不能硬转 Long，统一按 Number 读取
        Object cached = redisTemplate.opsForValue().get(RedisContext.QUESTION_TOTAL_KEY);
        if (cached instanceof Number number) {
            total = number.longValue();
        }
        if (cached == null) {
            boolean tryLock = redissonClient.getLock(LOCK_ADD_QUESTION).tryLock();
            RLock lock = redissonClient.getLock(LOCK_ADD_QUESTION);
            if (tryLock) {

                try {
                    Long count = Long.parseLong(questionMapper.selectCount(new QueryWrapper<Question>().eq("status", 1)).toString());
                    redisTemplate.opsForValue().set(RedisContext.QUESTION_TOTAL_KEY, count);
                    return count;
                } catch (Exception e) {

                    log.error("获取总数失败{}",e.getMessage());
                    throw new BizException(QuestionErrorCode.QUESTION_TOTAL_QUERY_FAILED, e);
                } finally {
                    lock.unlock();
                }

            }
        }

        return total;
    }

    @Transactional
    public Question addQuestion(InsertQuestionDto insertQuestionDto) {
        Long userId= UserContext.getUserId();
        if(userId==null){
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        validateOrdinaryQuestion(insertQuestionDto);
        String LockKey=LOCK_ADD_QUESTION+userId;
        RLock lock=redissonClient.getLock(LockKey);
        try{

            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if(!locked){
                throw new BizException(CommonErrorCode.RATE_LIMITED);
            }
            Question question = new Question(insertQuestionDto);
            question.setStatus(3);

            question.setCreateUserId(userId);
            question.setSource(UserContext.getUserName());
            question.setCreateUserId(UserContext.getUserId());
            question.setContentHash(DigestUtils.md5DigestAsHex(insertQuestionDto.getDescription().getBytes()));
            int insert = questionMapper.insert(question);
            if (insert > 0) {
                return question;
            }
            throw new BizException(QuestionErrorCode.QUESTION_SAVE_FAILED);
        } catch (BizException e) {
            throw e;
        } catch (DuplicateKeyException e) {
            // contentHash 唯一约束冲突：同一题目重复创建，原始 SQL 只进日志
            log.warn("重复创建题目, userId={}", userId, e);
            throw new BizException(CommonErrorCode.DUPLICATE_RESOURCE, e);
        } catch (Exception e) {
            throw new BizException(CommonErrorCode.INTERNAL_ERROR, e);
        }finally {
            if(lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
       }





    public QuestionVo getQuestionById(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if(question==null){
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }



        if(question.getStatus().equals(3)){
            Result<UserDto>userDtoResult=userFeignClient.getUserInfo(UserContext.getUserId());
            if(userDtoResult.getCode()!=200){
                throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
            }
            if(userDtoResult.getData()==null){
                throw new BizException(QuestionErrorCode.USER_NOT_FOUND);
            }
            if(!UserContext.getUserId().equals(question.getCreateUserId())&&!userDtoResult.getData().getRoleId().equals(2)){
                // 私密题仅创建者与管理员可见
                throw new BizException(CommonErrorCode.CONTENT_RESTRICTED);
            }
        }
        if(question.getStatus().equals(0)){
            throw new BizException(QuestionErrorCode.QUESTION_OFF_SHELF);
        }
        if(question.getStatus().equals(2)){
            throw new BizException(QuestionErrorCode.QUESTION_UNDER_REVIEW);
        }


        if(question.getQuestionType().equals(QuestionType.FUNCTION)){
            FunctionConfig functionConfig=functionConfigMapper.selectOne(new QueryWrapper<FunctionConfig>().eq("question_id", questionId));
            QuestionVo questionVo=new QuestionVo(question,functionConfig);
            return questionVo;
        }
        return new QuestionVo(question,null);

    }

    /** agent 批量题目详情单次上限：完整题干 payload 较大，控制单请求规模。 */
    private static final int AGENT_DETAIL_BATCH_MAX = 10;

    /**
     * agent 批量题目详情：按 ID 批量返回完整题目，逐题做可见性判定，
     * 不因个别题目不可见而整体报错。
     *
     * <p>可见性规则与 {@link #getQuestionById(Long)} 保持一致：
     * status==1 正常可见；status==3 私密题仅创建者与管理员（roleId=2）可见；
     * status==0 下架、status==2 审核中一律不可见（管理员亦同）。
     * 角色查询按批次去重：仅当批次中出现「他人私密题」时才调用一次用户服务，
     * 避免逐题 Feign 调用。</p>
     *
     * @param inputIds 题目 ID 列表（自动去重，≤10）
     * @return 逐题结果，顺序与入参一致；仅 state=ok 的条目携带完整题目
     */
    public List<AgentQuestionDetailVo> getQuestionDetailsBatch(List<Long> inputIds) {
        if(UserContext.getUserId()==null){
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        List<Long> distinct = inputIds == null ? Collections.emptyList()
                : inputIds.stream().filter(Objects::nonNull).distinct().toList();
        if(distinct.isEmpty()){
            throw new BizException(QuestionErrorCode.QUESTION_LIST_REQUIRED);
        }
        if(distinct.size() > AGENT_DETAIL_BATCH_MAX){
            throw new BizException(QuestionErrorCode.QUESTION_BATCH_LIMIT_EXCEEDED, AGENT_DETAIL_BATCH_MAX);
        }
        Map<Long, Question> questions = new HashMap<>();
        for(Question question : questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .in(Question::getQuestionId, distinct))){
            questions.put(question.getQuestionId(), question);
        }
        Long userId = UserContext.getUserId();
        boolean needRoleCheck = distinct.stream().anyMatch(id -> {
            Question question = questions.get(id);
            return question != null && Integer.valueOf(3).equals(question.getStatus())
                    && !userId.equals(question.getCreateUserId());
        });
        boolean isAdmin = false;
        if(needRoleCheck){
            Result<UserDto> userDtoResult = userFeignClient.getUserInfo(userId);
            if(userDtoResult.getCode()!=200){
                throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
            }
            if(userDtoResult.getData()==null){
                throw new BizException(QuestionErrorCode.USER_NOT_FOUND);
            }
            isAdmin = Integer.valueOf(2).equals(userDtoResult.getData().getRoleId());
        }
        List<AgentQuestionDetailVo> results = new ArrayList<>();
        for(Long id : distinct){
            Question question = questions.get(id);
            if(question == null){
                results.add(AgentQuestionDetailVo.notFound(id));
                continue;
            }
            if(Integer.valueOf(3).equals(question.getStatus())
                    && !userId.equals(question.getCreateUserId()) && !isAdmin){
                results.add(AgentQuestionDetailVo.invisible(id, "无权查看该题目"));
                continue;
            }
            if(Integer.valueOf(0).equals(question.getStatus())){
                results.add(AgentQuestionDetailVo.invisible(id, "题目已下架"));
                continue;
            }
            if(Integer.valueOf(2).equals(question.getStatus())){
                results.add(AgentQuestionDetailVo.invisible(id, "题目审核中"));
                continue;
            }
            FunctionConfig functionConfig = question.getQuestionType().equals(QuestionType.FUNCTION)
                    ? functionConfigMapper.selectOne(new QueryWrapper<FunctionConfig>().eq("question_id", id))
                    : null;
            results.add(AgentQuestionDetailVo.ok(id, new QuestionVo(question, functionConfig)));
        }
        return results;
    }

    @Transactional
    public Question updateQuestion(InsertQuestionDto insertQuestionDto,Long questionId) {
        requireQuestionAdmin();
        validateOrdinaryQuestion(insertQuestionDto);
        Question updatequestion= questionMapper.selectById(questionId);
        if(Objects.isNull(updatequestion)){
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }
        updatequestion.setTitle(insertQuestionDto.getTitle());
        updatequestion.setDescription(insertQuestionDto.getDescription());
        updatequestion.setInputDesc(insertQuestionDto.getInputDesc());
        updatequestion.setOutputDesc(insertQuestionDto.getOutputDesc());
        updatequestion.setSampleInput(insertQuestionDto.getSampleInput());
        updatequestion.setSampleOutput(insertQuestionDto.getSampleOutput());
        updatequestion.setHint(insertQuestionDto.getHint());
        updatequestion.setTags(insertQuestionDto.getTags());
        updatequestion.setTimeLimit(insertQuestionDto.getTimeLimit());
        updatequestion.setMemoryLimit(insertQuestionDto.getMemoryLimit());


        int update = questionMapper.updateById(updatequestion);
        if (update > 0) {
            return updatequestion;
        }
        throw new BizException(QuestionErrorCode.QUESTION_UPDATE_FAILED);
    }

    @Transactional
    public void deleteQuestion(Long questionId) {
        requireQuestionAdmin();
        Question question= questionMapper.selectById(questionId);
        if(Objects.isNull(question)){
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }

        int delete = questionMapper.deleteById(questionId);
        if (delete <= 0) {
            throw new BizException(QuestionErrorCode.QUESTION_DELETE_FAILED);
        }
    }

    private void requireQuestionAdmin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        Result<UserDto> userResult;
        try {
            userResult = userFeignClient.getUserInfo(userId);
        } catch (Exception e) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, e);
        }
        if (userResult == null || !Integer.valueOf(200).equals(userResult.getCode())) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (userResult.getData() == null) {
            throw new BizException(QuestionErrorCode.USER_NOT_FOUND);
        }
        Integer roleId = userResult.getData().getRoleId();
        if (!Integer.valueOf(2).equals(roleId) && !Integer.valueOf(0).equals(roleId)) {
            throw new BizException(CommonErrorCode.ADMIN_ONLY);
        }
    }

    /** 普通题目新增、更新共用校验，与 question 表的字符/字节容量保持一致。 */
    private void validateOrdinaryQuestion(InsertQuestionDto dto) {
        if (dto == null) {
            throw new BizException(CommonErrorCode.PARAM_MISSING);
        }
        if (!StringUtils.hasText(dto.getTitle())) {
            throw new BizException(QuestionErrorCode.QUESTION_TITLE_REQUIRED);
        }
        if (!StringUtils.hasText(dto.getDescription())) {
            throw new BizException(QuestionErrorCode.QUESTION_CONTENT_REQUIRED);
        }
        if (dto.getTitle().codePointCount(0, dto.getTitle().length()) > 200) {
            throw new BizException(QuestionErrorCode.QUESTION_TITLE_TOO_LONG, 200);
        }
        if (dto.getTags() != null && dto.getTags().codePointCount(0, dto.getTags().length()) > 255) {
            throw new BizException(QuestionErrorCode.QUESTION_TAGS_TOO_LONG, 255);
        }
        validateTextLength(dto.getInputDesc(), "输入描述");
        validateTextLength(dto.getOutputDesc(), "输出描述");
        validateTextLength(dto.getSampleInput(), "样例输入");
        validateTextLength(dto.getSampleOutput(), "样例输出");
        validateTextLength(dto.getHint(), "提示");
        if (dto.getTimeLimit() == null || dto.getTimeLimit() <= 0) {
            throw new BizException(QuestionErrorCode.QUESTION_TIME_LIMIT_INVALID);
        }
        if (dto.getMemoryLimit() == null || dto.getMemoryLimit() <= 0) {
            throw new BizException(QuestionErrorCode.QUESTION_MEMORY_LIMIT_INVALID);
        }
    }

    private void validateTextLength(String value, String fieldName) {
        // MySQL TEXT 的容量是字节数；中文与四字节字符不能按 String.length() 判断。
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > 65_535) {
            throw new BizException(QuestionErrorCode.QUESTION_TEXT_TOO_LONG, fieldName);
        }
    }

    public CursorPageResult<ReturnQuestionDto> cursorQuestions(Long lastId, Integer pageSize, String difficulty, Integer status, String title,String type) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<>();

        // 游标分页：如果传入了 lastId，则查询大于 lastId 的记录（升序）
        if (lastId != null) {
            wrapper.gt(Question::getQuestionId, lastId);  // 改为 gt（大于），从前往后
        }


        // 状态筛选
        if (status != null) {
            wrapper.eq(Question::getStatus, status);
        }

        // 标题搜索
        if (title != null && !title.isEmpty()) {
            wrapper.like(Question::getTitle, title);
        }
        // 类型搜索
        if (type != null&& !type.isEmpty()) {
            wrapper.eq(Question::getQuestionType, type);
        }
        if(difficulty!=null && !difficulty.isEmpty()) {
            wrapper.and(group -> {
                for (String value : difficulty.split(",")) {
                    group.or().eq(Question::getDifficulty, value);
                }
            });
        }

        // 按 questionId 升序排列（从小到大）
        wrapper.orderByAsc(Question::getQuestionId);  // 改为升序
        wrapper.last("LIMIT " + (pageSize + 1));


        List<Question> list = questionMapper.selectList(wrapper);
        List<ReturnQuestionDto> returnQuestionDtoList = new ArrayList<>();
        for(Question question:list){
            ReturnQuestionDto returnQuestionDto=new ReturnQuestionDto(question);
            returnQuestionDtoList.add(returnQuestionDto);
        }

        List<ReturnQuestionDto> records;

        Long nextCursor = null;
        Boolean hasNext = false;

        if (!list.isEmpty()) {
            if (list.size() > pageSize) {
                // 取前 pageSize 条作为当前页
                records = returnQuestionDtoList.subList(0, pageSize);
                // 获取最后一条记录的 ID 作为下一页的游标
                ReturnQuestionDto lastRecord = records.get(records.size() - 1);
                nextCursor = lastRecord.getQuestionId();
                hasNext = true;
            } else {
                records =  returnQuestionDtoList;
                hasNext = false;
            }
        } else {
            records = new ArrayList<>();
        }
        List<Long>questionIds = new ArrayList<>();
        for(ReturnQuestionDto returnQuestionDto:records){
            questionIds.add(returnQuestionDto.getQuestionId());
        }

        Map<Long, Integer> questionStatusMap = new HashMap<>();
        Long userId = UserContext.getUserId();
        if(userId != null && !questionIds.isEmpty()){
            List<SubmitRecord> submitRecords = submitRecordMapper.getQuestionSubmitStatusByQuestionIds(userId, questionIds);
            for(SubmitRecord submitRecord:submitRecords){
                questionStatusMap.put(submitRecord.getQuestionId(), "AC".equals(submitRecord.getSubmitStatus()) ? 1 : 2);
            }
        }
        for(ReturnQuestionDto record:records){
            // 0-未尝试，1-已通过，3-尝试过但未通过
            record.setStatus(questionStatusMap.getOrDefault(record.getQuestionId(), 0));
        }

        return CursorPageResult.<ReturnQuestionDto>builder()
                .records(records)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }
    public List< QuestionVo> serach(String likeKey) {

        List<QuestionVo> questionVoList = new ArrayList<>();
        List<Question> questions = questionMapper.selectList(new QueryWrapper<Question>().like("title",likeKey).or().like("tags",likeKey).orderByAsc("question_id"));
        for(Question question:questions){
            questionVoList.add(new QuestionVo(question));
        }
        return questionVoList;
    }
}
