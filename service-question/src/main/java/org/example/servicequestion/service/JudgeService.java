package org.example.servicequestion.service;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.question.TestMessage;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicecommon.RedisDto.DebugDto;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.GetCodeDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.entry.TestCase;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.outboxpro.core.OutboxProPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    private RedisTemplate<String,Object> redisTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private GroupQuestionPermissionService groupQuestionPermissionService;

    /**
     * 校验当前用户是否可以提交该题目。
     *
     * <p>公开题目直接放行；私密题（status=3）交给
     * {@link GroupQuestionPermissionService} 判断创建者/组员/管理员身份。
     * 不可见时统一返回「资源不存在」，避免向调用方泄露题目是否存在。</p>
     *
     * @param questionId 题目 id
     */
    private void assertSubmittable(Long questionId){
        Question question=questionMapper.selectById(questionId);
        if(question==null)throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND);
        if(Integer.valueOf(1).equals(question.getStatus()))return;
        if(Integer.valueOf(3).equals(question.getStatus())
                && groupQuestionPermissionService.canAccessPrivateQuestion(question)){
            return;
        }
        throw new BizException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /**
     * 抽出小方法同事务发送消息并入库
     * @param submitRecord 已经初始化可以直接入库的记录表
     */
    private Long  addSubmitAndSend(SubmitRecord submitRecord){
        int r= submitRecordMapper.insert(submitRecord);
        if(r==0)throw new BizException(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE);

        // 判题请求改走事务性 Outbox：payload 只放 submitRecordId（小字段引用），
        // 路由（judge.exchange/judge.routing）在 OutboxEventRouteConfig 登记
        outboxPublisher.publish(EventTypes.JUDGE_SUBMIT_REQUEST, submitRecord.getSubmitRecordId());
        return submitRecord.getSubmitRecordId();
    }




    /**
     * 提交判题：提交记录落库与判题请求事件在同一事务内写入（事务性 Outbox），
     * 事务提交后由 Relay 异步投递到判题交换机，避免「DB 已提交但消息丢失」。
     */
    public Long judge(GetCodeDto getCodeDto) {

        String code = getCodeDto.getCode();
        String language = getCodeDto.getLanguage();
        Long questionId = getCodeDto.getQuestionId();
        String title=getCodeDto.getQuestionTitle();
        if(questionId == null) {
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }
        // 先校验提交场景：非法场景不应查库，更不应触发小组 Feign 调用
        String submitScene = getType(getCodeDto.getSubmitScene());
        assertSubmittable(questionId);
        //TODO 在此后续增加如果是小组题目新加小组到记录的索引
        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitContent(code);
        submitRecord.setLanguage(language);
        submitRecord.setQuestionTitle(title);
        submitRecord.setSubmitTime(LocalDateTime.now());
        submitRecord.setJudgeStatus("pending"); //pending/failue/success
        submitRecord.setQuestionId(questionId);
        submitRecord.setUserId(UserContext.getUserId());
        submitRecord.setSubmitScene(submitScene);
        Long ans;
        try{
             ans = transactionTemplate.execute(status -> {
                        try {
                            return addSubmitAndSend(submitRecord);
                        } catch (RuntimeException e) {
                            status.setRollbackOnly();
                            throw e;
                        }
                    }
            );
        }catch (BizException e){
            throw e;
        }catch (Exception e){
            log.error("判题提交失败", e);
            throw new BizException(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE, e);
        }
        if(ans==null){
            throw new BizException(QuestionErrorCode.JUDGE_SERVICE_UNAVAILABLE);
        }
        return ans;

    }


    /**
     * 归一化提交场景。
     *
     * @param type 客户端提交的 submitScene（NORMAL/REVIEW/PLAN）
     * @return 归一化后的场景字符串
     * @throws BizException 场景缺失（SUBMIT_SCENE_REQUIRED）或不在枚举内
     *         （SUBMIT_SCENE_UNSUPPORTED）——缺失时必须返回明确业务错误，
     *         不能进入 switch 触发 NPE（否则客户端漏传字段拿到 500 而非 400 语义）
     */
    private String getType(String type){
         if (type == null || type.isBlank()) {
             throw new BizException(QuestionErrorCode.SUBMIT_SCENE_REQUIRED);
         }
         return switch (type){
             case "REVIEW" -> "REVIEW";
             case "NORMAL" -> "NORMAL";
             case  "PLAN" -> "PLAN";
             default -> throw new BizException(QuestionErrorCode.SUBMIT_SCENE_UNSUPPORTED);
         };
    }


    /**
     * 调试判题：Redis 任务写入先行，Outbox 事件随后在同一事务内登记，
     * 事务提交后由 Relay 投递调试路由键，保证任务数据与消息的一致性。
     */
    public String debug(DebugDto debugDto){
        String uuid = UUID.randomUUID().toString();
        assertSubmittable(debugDto.getQuestionId());
        debugDto.setUserId(UserContext.getUserId());
        redisTemplate.opsForHash().put(RedisContext.JUDGE_DEBUG_KEY,uuid,debugDto);
        outboxPublisher.publish(EventTypes.JUDGE_DEBUG_REQUEST, uuid);
        return uuid;
    }


    private static @NonNull List<TestMessage> getTestMessages(List<TestCase> list) {
        List<TestMessage>testMessages=new ArrayList<>();
        for(TestCase testCase: list){
            TestMessage testMessage=new TestMessage();
            testMessage.setInputData(testCase.getInputData());
            testMessage.setExpectedOutput(testCase.getExpectedOutput());
            testMessage.setMemoryLimit(testCase.getMemoryLimit());
            testMessage.setScoreWeight(testCase.getScoreWeight());
            testMessage.setSortOrder(testCase.getSortOrder());
            testMessages.add(testMessage);
        }
        return testMessages;
    }
}
