package org.example.servicequestion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.CursorPageResult;
import org.example.servicequestion.dto.InsertQuestionDto;
import org.example.servicequestion.dto.ReturnQuestionDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.ArrayList;
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


    public Long getTotalQuestionCount() throws InterruptedException {

        Object total =redisTemplate.opsForValue().get(RedisContext.QUESTION_TOTAL_KEY);
        if (total == null) {
            boolean tryLock = redissonClient.getLock(LOCK_ADD_QUESTION).tryLock();
            RLock lock = redissonClient.getLock(LOCK_ADD_QUESTION);
            if (tryLock) {

                try {
                    Long count = Long.parseLong(questionMapper.selectCount(new QueryWrapper<Question>().eq("status", 1)).toString());
                    redisTemplate.opsForValue().set(RedisContext.QUESTION_TOTAL_KEY, count);
                    return count;
                } catch (Exception e) {

                    log.error("获取总数失败{}",e.getMessage());
                    throw new RuntimeException(e);
                    //throw new IllegalStateException("获取题目总数失败，请稍后重试");
                } finally {
                    lock.unlock();
                }

            }
        }

        return 0L;
    }

    @Transactional
    public Question addQuestion(InsertQuestionDto insertQuestionDto) {
        Long userId= UserContext.getUserId();
        if(userId==null){
            throw new RuntimeException("请登录后操作");
        }
        String LockKey=LOCK_ADD_QUESTION+userId;
        RLock lock=redissonClient.getLock(LockKey);
        try{

            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if(!locked){
                throw new RuntimeException("请勿频繁操作");
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
            throw new RuntimeException("添加失败");
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }finally {
            if(lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
       }


    public Question getQuestionById(Long questionId) {
        Question question = questionMapper.selectById(questionId);
        if(question==null){
            throw new RuntimeException("题目不存在");
        }



        if(question.getStatus().equals(3)){
            Result<UserDto>userDtoResult=userFeignClient.getUserInfo(UserContext.getUserId());
            if(userDtoResult.getCode()!=200){
                throw new RuntimeException(userDtoResult.getMessage());
            }
            if(userDtoResult.getData()==null){
                throw new RuntimeException("未找到用户");
            }
            if(!UserContext.getUserId().equals(question.getCreateUserId())&&!userDtoResult.getData().getRoleId().equals(2)){
                throw new RuntimeException("无权查看该题目");
            }
        }
        if(question.getStatus().equals(0)){
            throw new RuntimeException("题目已下架");
        }
        if(question.getStatus().equals(2)){
            throw new RuntimeException("题目审核中");
        }
        return question;


    }

    @Transactional
    public Question updateQuestion(InsertQuestionDto insertQuestionDto,Long questionId) {
        Result<UserDto> userDto=userFeignClient.getUserInfo(UserContext.getUserId());
        if(Objects.isNull(userDto)){
            throw new RuntimeException("用户不存在");
        }
        Question updatequestion= questionMapper.selectById(questionId);
        if(Objects.isNull(updatequestion)){
            throw new RuntimeException("未找到该题目");
        }
        if(!updatequestion.getCreateUserId().equals(UserContext.getUserId())&&!userDto.getData().getRoleId().equals(2)){
            throw new RuntimeException("无权修改");
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
        throw new RuntimeException("更新失败");
    }

    @Transactional
    public void deleteQuestion(Long questionId) {
        Result<UserDto> userDto= userFeignClient.getUserInfo(UserContext.getUserId());
        if(userDto.getCode()!=200){
            throw new RuntimeException(userDto.getMessage());
        }
        System.out.println(userDto+"----"+UserContext.getUserId());
        if(!Objects.equals(userDto.getData().getUserId(), UserContext.getUserId().toString())){
            if(userDto.getData().getRoleId()==1)
                throw  new RuntimeException("无资格删除非己题目");
        }

        int delete = questionMapper.deleteById(questionId);
        if (delete <= 0) {
            throw new RuntimeException("删除失败");
        }
    }

    public CursorPageResult<ReturnQuestionDto> cursorQuestions(Long lastId, Integer pageSize, Integer difficulty, Integer status, String title) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<>();

        // 游标分页：如果传入了 lastId，则查询大于 lastId 的记录（升序）
        if (lastId != null) {
            wrapper.gt(Question::getQuestionId, lastId);  // 改为 gt（大于），从前往后
        }

        // 难度筛选
        if (difficulty != null) {
            wrapper.eq(Question::getDifficulty, difficulty);
        }

        // 状态筛选
        if (status != null) {
            wrapper.eq(Question::getStatus, status);
        }

        // 标题搜索
        if (title != null && !title.isEmpty()) {
            wrapper.like(Question::getTitle, title);
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

        if (list != null && !list.isEmpty()) {
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
}
