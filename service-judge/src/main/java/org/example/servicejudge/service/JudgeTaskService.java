package org.example.servicejudge.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.ai.AiAdviceWADto;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.outbox.OutboxService;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.Util.CodeBuild;
import org.example.servicejudge.entry.*;
import org.example.servicejudge.enums.QuestionType;
import org.example.servicejudge.functionsService.Java;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 判题任务服务：合并 Submit / Retry 两条链路中逐行重复的
 * 「测试用例查询 -> 代码组装 -> Docker 判题 -> judge_record 落库 -> 结果事件发布」。
 *
 * <p>事务纪律（关键）：Docker 判题执行绝不能在数据库事务内。流程为
 * 无事务领取(CAS) -> 无事务执行判题 -> {@code @Transactional}{插 judge_record +
 * Outbox 事件}。事务方法 {@link #persistResultWithEvents} 通过 {@code self}
 * 代理调用，禁止同类直接调用绕过代理。</p>
 *
 * <p>事件发布统一走 {@link OutboxService#append}：与 judge_record 插入同事务提交，
 * 由 OutboxRelay 异步投递，保证「库里有结果则必有回调」。</p>
 */
@Service
@Slf4j
public class JudgeTaskService {

    private final JudgeInterface judge;
    private final SubmitRecordMapper submitRecordMapper;
    private final TestCaseMapper testCaseMapper;
    private final JudgeRecordMapper judgeRecordMapper;
    private final QuestionMapper questionMapper;
    private final FunctionConfigMapper functionConfigMapper;
    private final FunctionTestCaseMapper functionTestCaseMapper;
    private final OutboxService outboxService;

    /** 代理自注入：保证 {@link #persistResultWithEvents} 的事务经代理生效。 */
    @Autowired
    @Lazy
    private JudgeTaskService self;

    public JudgeTaskService(
            JudgeInterface judge,
            SubmitRecordMapper submitRecordMapper,
            TestCaseMapper testCaseMapper,
            JudgeRecordMapper judgeRecordMapper,
            QuestionMapper questionMapper,
            FunctionConfigMapper functionConfigMapper,
            FunctionTestCaseMapper functionTestCaseMapper,
            OutboxService outboxService
    ) {
        this.judge = judge;
        this.submitRecordMapper = submitRecordMapper;
        this.testCaseMapper = testCaseMapper;
        this.judgeRecordMapper = judgeRecordMapper;
        this.questionMapper = questionMapper;
        this.functionConfigMapper = functionConfigMapper;
        this.functionTestCaseMapper = functionTestCaseMapper;
        this.outboxService = outboxService;
    }

    /**
     * CAS 领取提交记录（无事务）：pending -> judging。
     *
     * @param submitRecordId 提交记录主键
     * @return 领取成功的提交记录；记录不存在、已 success（消息重复投递）或
     *         CAS 被其他消费者抢占时返回 null
     */
    public SubmitRecord claimSubmitRecord(Long submitRecordId) {
        SubmitRecord submitRecord = submitRecordMapper.selectById(submitRecordId);
        if (submitRecord == null) {
            log.info("提交记录不存在，submitId={}", submitRecordId);
            return null;
        }
        if ("success".equals(submitRecord.getJudgeStatus())) {
            log.info("消息已处理，submitId={}", submitRecordId);
            return null;
        }
        if (submitRecordMapper.updateRecordToJudge(submitRecordId) != 1) {
            log.info("提交记录被其他消费者抢占或状态非 pending，submitId={}", submitRecordId);
            return null;
        }
        return submitRecord;
    }

    /**
     * Submit 主流程入口：领取 -> 判题 -> 落库与事件发布（WA/RE/TLE 追加 AI 建议事件）。
     *
     * @param submitRecordId 提交记录主键
     * @return 判题结果记录；领取阶段幂等短路（见 {@link #claimSubmitRecord}）返回 null
     * @throws IOException     判题执行失败
     * @throws IllegalStateException 题目/配置缺失、结果入库失败等业务异常
     */
    public JudgeRecord handleSubmit(Long submitRecordId) throws IOException {
        SubmitRecord submitRecord = claimSubmitRecord(submitRecordId);
        if (submitRecord == null) {
            return null;
        }
        return judgeAndPersist(submitRecord, true);
    }

    /**
     * 判题并持久化（无事务外壳）：先无事务执行 Docker 判题，再经代理调用事务方法
     * 完成「插 judge_record + Outbox 事件」。Retry 链路以 sendAiAdvice=false 复用。
     *
     * @param submitRecord  已领取（judging 状态）的提交记录
     * @param sendAiAdvice 结果为 WA/RE/TLE 时是否追加 AI 建议事件（Submit 流程为 true，
     *                     Retry 流程维持现状不发）
     * @return 已落库并回填主键的判题结果
     * @throws IOException     判题执行失败
     * @throws IllegalStateException 题目/配置缺失、结果入库失败等业务异常
     */
    public JudgeRecord judgeAndPersist(SubmitRecord submitRecord, boolean sendAiAdvice) throws IOException {
        JudgeRecord finalResult = executeJudge(submitRecord);
        // 必须经代理调用，保证插入与事件写入在同一事务
        self.persistResultWithEvents(submitRecord, finalResult, sendAiAdvice);
        return finalResult;
    }

    /**
     * 按题目类型分派判题（纯执行，无数据库事务，绝不在此方法上加 @Transactional）：
     * 查询测试用例、组装函数模式代码、调用判题器。
     */
    private JudgeRecord executeJudge(SubmitRecord submitRecord) throws IOException {
        Question question = questionMapper.selectById(submitRecord.getQuestionId());
        if (question == null || question.getQuestionType() == null) {
            throw new IllegalStateException("题目或题目类型不存在，questionId=" + submitRecord.getQuestionId());
        }

        if (QuestionType.ACM.equals(question.getQuestionType())) {
            return judgeAcm(submitRecord);
        }
        if (QuestionType.FUNCTION.equals(question.getQuestionType())) {
            return judgeFunction(submitRecord);
        }
        throw new IllegalStateException("不支持的题目类型：" + question.getQuestionType());
    }

    /**
     * 事务方法：插入 judge_record 并通过 Outbox 登记结果回调/AI 建议事件。
     * 事件行与业务写入同事务提交，由 OutboxRelay 异步投递。
     * 必须经 Spring 代理调用（见 {@link #judgeAndPersist}）。
     *
     * @param submitRecord  提交记录
     * @param finalResult   判题结果（本事务内回填主键）
     * @param sendAiAdvice  是否在 WA/RE/TLE 时登记 AI 建议事件
     * @throws IllegalStateException 插入失败或主键未回填（整体回滚）
     */
    @Transactional
    public void persistResultWithEvents(
            SubmitRecord submitRecord,
            JudgeRecord finalResult,
            boolean sendAiAdvice
    ) {
        prepareAndInsertResult(finalResult, submitRecord);
        publishResultEvents(submitRecord, finalResult, sendAiAdvice);
    }

    /**
     * 通过 Outbox 登记判题结果事件（必须在数据库事务内调用）：
     * <ul>
     *   <li>JUDGE_RESULT_CALLBACK -> question（payload 为 judgeRecordId）；</li>
     *   <li>sendAiAdvice 且结果为 WA/RE/TLE 时，AI_ADVICE_REQUEST -> ai
     *       （payload 为瘦身后的事件引用 DTO，service-ai 按 judgeRecordId 拉取大字段）。</li>
     * </ul>
     *
     * @param submitRecord 提交记录
     * @param finalResult  已回填主键的判题结果
     * @param sendAiAdvice 是否登记 AI 建议事件
     */
    public void publishResultEvents(SubmitRecord submitRecord, JudgeRecord finalResult, boolean sendAiAdvice) {
        outboxService.append(
                EventTypes.JUDGE_RESULT_CALLBACK,
                MqContexts.Question_EXCHANGE,
                MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY,
                finalResult.getJudgeRecordId());

        String submitStatus = finalResult.getSubmitStatus();
        if (sendAiAdvice
                && ("WA".equals(submitStatus) || "RE".equals(submitStatus) || "TLE".equals(submitStatus))) {
            String messageId = "ai_advice" + submitRecord.getQuestionId()
                    + ":" + submitRecord.getUserId()
                    + ":" + finalResult.getJudgeRecordId();
            AiAdviceWADto aiAdviceWADto = AiAdviceWADto.builder()
                    .userId(submitRecord.getUserId())
                    .submitId(submitRecord.getSubmitRecordId())
                    .questionId(submitRecord.getQuestionId())
                    .judgeRecordId(finalResult.getJudgeRecordId())
                    .messageId(messageId)
                    .language(submitRecord.getLanguage())
                    .judgeStatus(submitStatus)
                    .build();
            outboxService.append(
                    EventTypes.AI_ADVICE_REQUEST,
                    MqContexts.Ai_EXCHANGE,
                    MqContexts.AI_WA_ADVICE_ROUTING_KEY,
                    aiAdviceWADto);
        }
    }

    /**
     * 补齐提交关联字段并持久化判题结果，同时校验主键回填（失败抛异常整体回滚）。
     */
    private void prepareAndInsertResult(JudgeRecord finalResult, SubmitRecord submitRecord) {
        if (finalResult == null) {
            throw new IllegalStateException("判题器返回空结果，submitId=" + submitRecord.getSubmitRecordId());
        }
        finalResult.setSubmitRecordId(submitRecord.getSubmitRecordId());
        finalResult.setCode(submitRecord.getSubmitContent());
        finalResult.setCreateTime(LocalDateTime.now());

        int inserted = judgeRecordMapper.insert(finalResult);
        if (inserted != 1 || finalResult.getJudgeRecordId() == null) {
            throw new IllegalStateException("判题结果入库失败，submitId=" + submitRecord.getSubmitRecordId());
        }
    }

    /**
     * ACM 模式判题：查询全部测试用例后整体送入判题器。
     */
    private JudgeRecord judgeAcm(SubmitRecord submitRecord) throws IOException {
        List<TestCase> testCases = testCaseMapper.selectList(
                new QueryWrapper<TestCase>().eq("question_id", submitRecord.getQuestionId()));

        JudgeRecord finalResult = judge.batchExecuteCode(
                submitRecord.getSubmitContent(),
                submitRecord.getLanguage(),
                toTestDtos(testCases));
        finalResult.setTestTotal(testCases.size());
        return finalResult;
    }

    /**
     * 函数模式判题：读取函数配置，生成 Main 调用器并包装用户代码后批量执行。
     *
     * @throws InterruptedIOException 函数配置缺失或语言不支持
     */
    private JudgeRecord judgeFunction(SubmitRecord submitRecord) throws IOException {
        List<FunctionTestCase> testCases = functionTestCaseMapper.selectList(
                new QueryWrapper<FunctionTestCase>().eq("question_id", submitRecord.getQuestionId()));

        FunctionConfig functionConfig = functionConfigMapper.selectOne(
                new QueryWrapper<FunctionConfig>().eq("question_id", submitRecord.getQuestionId()));
        if (functionConfig == null) {
            throw new InterruptedIOException("函数模式题目配置不存在");
        }
        if (!"java".equalsIgnoreCase(submitRecord.getLanguage())) {
            throw new InterruptedIOException("函数模式暂时只支持 Java");
        }

        String main = Java.ToMain(
                functionConfig.getParameterConfig(),
                functionConfig.getMethodName(),
                functionConfig.getOutputType());
        String code = CodeBuild.build(
                submitRecord.getSubmitContent(),
                functionConfig.getParameterConfig(),
                functionConfig.getOutputType());

        JudgeRecord finalResult = judge.batchExecuteCode(
                code,
                main,
                submitRecord.getLanguage(),
                toFunctionTestDtos(testCases));
        finalResult.setTestTotal(testCases.size());
        return finalResult;
    }

    private List<TestDto> toTestDtos(List<TestCase> testCases) {
        List<TestDto> testDtos = new ArrayList<>();
        for (TestCase testCase : testCases) {
            testDtos.add(new TestDto(testCase));
        }
        return testDtos;
    }

    private List<TestDto> toFunctionTestDtos(List<FunctionTestCase> testCases) {
        List<TestDto> testDtos = new ArrayList<>();
        for (FunctionTestCase testCase : testCases) {
            testDtos.add(new TestDto(testCase));
        }
        return testDtos;
    }
}
