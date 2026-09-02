package org.example.servicereview.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicecommon.dto.ReviewMasteredDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.outboxpro.core.OutboxProPublisher;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.ReviewConfigDto;
import org.example.servicereview.dto.UpdateReviewDto;
import org.example.servicereview.entry.Review;
import org.example.servicereview.entry.ReviewConfig;
import org.example.servicereview.entry.ReviewRecord;
import org.example.servicereview.mapper.ReviewConfigMapper;
import org.example.servicereview.mapper.ReviewMapper;
import org.example.servicereview.mapper.ReviewRecordMapper;
import org.example.servicereview.vo.ReviewRecordVo;
import org.example.servicereview.vo.ReviewVo;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ReviewService {
    @Autowired
    private ReviewConfigMapper reviewConfigMapper;
    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private ReviewRecordMapper reviewRecordMapper;
    @Autowired
    private QuestionFeignClient  questionFeignClient;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ConsumedEventService consumedEventService;
    @Autowired
    private OutboxProPublisher outboxPublisher;
    private final BigDecimal EFLOW= BigDecimal.valueOf(1.3);
    private final BigDecimal DEFAULT_EASINESS_FACTOR = BigDecimal.valueOf(2.5);
    /** 复习掌握事件时间字段格式（MQ 转换器无 Java 时间模块，统一走字符串） */
    private static final DateTimeFormatter MASTERED_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     *
     * 添加题目到复习中
     */
    public void addQuestionToReview(Long questionId){
        try {
            Review review = Review.builder().questionId(questionId).userId(UserContext.getUserId())
                    .lastQuality(0).lastReviewTime(LocalDateTime.now()).nextReviewTime(LocalDateTime.now().plusDays(1))
                    .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build();
            int r= reviewMapper.insert(review);
            if(r!=1){
                throw new IllegalArgumentException("添加失败请稍后重试");
            }
        }catch (DuplicateKeyException e){
            throw new IllegalArgumentException("该题目已在您的复习计划");
        }
    }


    /**
     * 获取当前登录用户的复习配置；如果不存在，则创建默认配置。
     *
     * @return 当前用户的复习配置
     */
    public ReviewConfig getCurrentReviewConfig() {
        return getReviewConfig(UserContext.getUserId());
    }


    /**
     * 新增或更新当前登录用户的复习配置。
     * <p>
     * 如果用户还没有配置记录，则使用请求参数和默认值创建一份配置；
     * 如果配置已存在，则只更新 DTO 中非空的字段。
     * </p>
     *
     * @param reviewConfigDto 复习配置修改请求，允许部分字段为空，为空表示不修改该字段
     * @return 新建或更新后的复习配置
     * @throws IllegalArgumentException 当请求对象为空时抛出
     */
    public ReviewConfig updateReviewConfig(ReviewConfigDto reviewConfigDto){
        if (reviewConfigDto == null) {
            throw new IllegalArgumentException("复习配置不能为空");
        }
        validateReviewConfig(reviewConfigDto);

        ReviewConfig reviewConfig = reviewConfigMapper.selectOne(new QueryWrapper<ReviewConfig>().eq("user_id",UserContext.getUserId()));
        if(reviewConfig==null){
            reviewConfig=ReviewConfig.builder()
                    .userId(UserContext.getUserId())
                    .reviewCount(reviewConfigDto.getReviewCount() == null ? Integer.MAX_VALUE : reviewConfigDto.getReviewCount())
                    .enableAutoReview(reviewConfigDto.getEnableAutoReview() == null ? 1 : reviewConfigDto.getEnableAutoReview())
                    .countCompileError(reviewConfigDto.getCountCompileError() == null ? 1 : reviewConfigDto.getCountCompileError())
                    .minEasinessFactor(reviewConfigDto.getMinEasinessFactor() == null ? new BigDecimal("1.30") : reviewConfigDto.getMinEasinessFactor())
                    .initialEasinessFactor(reviewConfigDto.getInitialEasinessFactor() == null ? new BigDecimal("2.50") : reviewConfigDto.getInitialEasinessFactor())
                    .masteredIntervalDays(reviewConfigDto.getMasteredIntervalDays() == null ? 30 : reviewConfigDto.getMasteredIntervalDays())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            validateReviewConfigValues(reviewConfig);
            reviewConfigMapper.insert(reviewConfig);
            return reviewConfig;
        }

        if (reviewConfigDto.getReviewCount() != null) {
            reviewConfig.setReviewCount(reviewConfigDto.getReviewCount());
        }
        if (reviewConfigDto.getEnableAutoReview() != null) {
            reviewConfig.setEnableAutoReview(reviewConfigDto.getEnableAutoReview());
        }
        if (reviewConfigDto.getCountCompileError() != null) {

            reviewConfig.setCountCompileError(reviewConfigDto.getCountCompileError());
        }
        if (reviewConfigDto.getMinEasinessFactor() != null) {
            reviewConfig.setMinEasinessFactor(reviewConfigDto.getMinEasinessFactor());
        }
        if (reviewConfigDto.getInitialEasinessFactor() != null) {
            reviewConfig.setInitialEasinessFactor(reviewConfigDto.getInitialEasinessFactor());
        }
        if (reviewConfigDto.getMasteredIntervalDays() != null) {
            reviewConfig.setMasteredIntervalDays(reviewConfigDto.getMasteredIntervalDays());
        }

        validateReviewConfigValues(reviewConfig);
        reviewConfig.setUpdateTime(LocalDateTime.now());
        reviewConfigMapper.updateById(reviewConfig);
        return reviewConfig;
    }

    private void validateReviewConfig(ReviewConfigDto config) {
        if (config.getReviewCount() != null && config.getReviewCount() < 0) {
            throw new IllegalArgumentException("每日复习数量不能小于 0");
        }
        if (config.getEnableAutoReview() != null
                && config.getEnableAutoReview() != 0
                && config.getEnableAutoReview() != 1) {
            throw new IllegalArgumentException("是否自动复习只能是 0 或 1");
        }
        if (config.getCountCompileError() != null
                && config.getCountCompileError() != 0
                && config.getCountCompileError() != 1) {
            throw new IllegalArgumentException("是否统计编译错误只能是 0 或 1");
        }
        if (config.getMinEasinessFactor() != null
                && config.getMinEasinessFactor().compareTo(new BigDecimal("1.30")) < 0) {
            throw new IllegalArgumentException("最低难度因子不能小于 1.3");
        }
        if (config.getInitialEasinessFactor() != null
                && config.getMinEasinessFactor() != null
                && config.getInitialEasinessFactor().compareTo(config.getMinEasinessFactor()) < 0) {
            throw new IllegalArgumentException("初始难度因子不能小于最低难度因子");
        }
        if (config.getMasteredIntervalDays() != null && config.getMasteredIntervalDays() <= 0) {
            throw new IllegalArgumentException("掌握间隔天数必须大于 0");
        }
    }

    private void validateReviewConfigValues(ReviewConfig config) {
        if (config.getInitialEasinessFactor().compareTo(config.getMinEasinessFactor()) < 0) {
            throw new IllegalArgumentException("初始难度因子不能小于最低难度因子");
        }
    }


    /**
     * 获取当前登录用户今天的复习题目列表。
     * <p>
     * 业务语义：每日第一次进入复习模块时，会基于 {@link Review} 中到期的题目生成当天的
     * {@link ReviewRecord} 快照；之后当天再次进入时，直接读取当天快照。
     * </p>
     * <p>
     * 返回结果包含当天快照中的待复习题目和已完成题目，方便前端展示当天完整复习列表。
     * 如果并发请求导致当天快照被其他线程先创建，会重新查询当天记录并返回。
     * </p>
     *
     * @return 今日复习题目详情列表；如果今天没有待复习题目则返回空列表
     */
    public Map<String,Object> getAllQuestions(){
        Map<String,Object> map = new HashMap<>();
        ReviewRecord reviewRecord=reviewRecordMapper.getTodayRecord(UserContext.getUserId());
        if(reviewRecord!=null){
            map.put("reviewRecord",reviewRecord);
            List<Long>questionIds=new ArrayList<>();
            if(reviewRecord.getPendingReviewQuestionIds()!=null){
                questionIds.addAll(reviewRecord.getPendingReviewQuestionIds());
            }
            if(questionIds.isEmpty()){
                map.put("questions",Collections.emptyList());

                return map;
            }
            Result<List<QuestionDto>>questionDtoResult= questionFeignClient.getFavorites(questionIds);
        if(questionDtoResult.getCode()!=200){
            throw new IllegalArgumentException(questionDtoResult.getMessage());
        }
        map.put("questions",questionDtoResult.getData());
        return map;
        }
        //今天第一次创建record
        ReviewConfig reviewConfig=reviewConfigMapper.selectOne(new QueryWrapper<ReviewConfig>().eq("user_id", UserContext.getUserId()));
        if(reviewConfig==null){
             reviewConfig=ReviewConfig.builder().userId(UserContext.getUserId()).reviewCount(Integer.MAX_VALUE)
                    .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build();
             reviewConfigMapper.insert(reviewConfig);
        }
        List<Review>reviews= reviewMapper.getReviews(reviewConfig.getReviewCount(),UserContext.getUserId());
        if(reviews==null||reviews.isEmpty()){
            map.put("questions",Collections.emptyList());
            return map;
        }
        List<Long>questionIds=reviews.stream().map(Review::getQuestionId).toList();

        Result<List<QuestionDto>>questionDtoResult= questionFeignClient.getFavorites(questionIds);
        if(questionDtoResult.getCode()!=200){
            throw new IllegalArgumentException(questionDtoResult.getMessage());
        }


        //获取上一天的记录
        ReviewRecord lastRecord=reviewRecordMapper.getlastRecord(UserContext.getUserId());
        //创建记录
        ReviewRecord record=new ReviewRecord();
        record.setUserId(UserContext.getUserId());
        record.setCreateTime(LocalDateTime.now());
        record.setReviewDate(LocalDate.now());
        record.setPendingReviewQuestionIds(questionDtoResult.getData().stream().map(QuestionDto::getQuestionId).toList());
        record.setCompletedReviewQuestionIds(Collections.emptyList());
        record.setAcQuestionIds(Collections.emptyList());
        if(lastRecord != null && lastRecord.getReviewDate() != null
                && lastRecord.getReviewDate().equals(LocalDate.now().minusDays(1))){
            record.setReviewDays(lastRecord.getReviewDays()+1);
        }
        else{
            record.setReviewDays(1);
        }
        try{
            int r=reviewRecordMapper.insert(record);
            if(r==0){
                throw new IllegalArgumentException("获取今天记录失败请稍后重试");
            }
        } catch (DuplicateKeyException e){
            log.warn("今日复习记录已被并发创建, userId: {}", UserContext.getUserId());
            ReviewRecord concurrentRecord=reviewRecordMapper.getTodayRecord(UserContext.getUserId());
            if(concurrentRecord!=null){
                List<Long>concurrentQuestionIds=new ArrayList<>();
                if(concurrentRecord.getPendingReviewQuestionIds()!=null){
                    concurrentQuestionIds.addAll(concurrentRecord.getPendingReviewQuestionIds());
                }
                if(concurrentRecord.getCompletedReviewQuestionIds()!=null){
                    concurrentQuestionIds.addAll(concurrentRecord.getCompletedReviewQuestionIds());
                }

                if(concurrentQuestionIds.isEmpty()){
                    map.put("questions",Collections.emptyList());
                    return map;
                }
                Result<List<QuestionDto>> concurrentResult= questionFeignClient.getFavorites(concurrentQuestionIds);
                if(concurrentResult.getCode()!=200){
                    throw new IllegalArgumentException(concurrentResult.getMessage());
                }
                map.put("questions",concurrentResult.getData());
                return map;
            }
            throw e;
        }

        map.put("reviewRecord",record);
        map.put("questions",questionDtoResult.getData());
        return map;
    }


    /**
     * 消费复习判题结果消息，并根据判题结果推进用户的复习状态。
     * <p>
     * 该方法监听 review 队列，只处理 {@link MqContexts#REVIEW_JUDGE_RECORD_ROUTING_KEY}
     * 对应的消息。消息体经 {@link EnvelopeCodec#unwrap} 做「统一信封 / 裸 DTO」双读，
     * 灰度期间两种格式均可解析。
     * </p>
     * <p>
     * 事件级幂等：以 {@code review:judge:{judgeRecordId}} 作为 consumed_event 的幂等键。
     * claim 与业务更新在同一个数据库事务内执行——事务回滚时 claim 占位行随之消失，
     * 消息重投后可重新接管；业务成功后 complete 也同事务提交，保证「业务提交」与
     * 「幂等行转 COMPLETED」原子。COMPLETED 是唯一跳过态；PROCESSING/FAILED 残留行
     * 在重投时按 RECLAIM 重新执行，重复副作用由今日快照成员判断兜底。
     * </p>
     * <p>
     * 处理流程：
     * <ol>
     *     <li>校验 routingKey，非复习判题消息直接 ACK 忽略；</li>
     *     <li>读取用户复习配置，如果关闭自动复习，则 ACK 后跳过；</li>
     *     <li>根据判题结果计算 SM-2 quality；</li>
     *     <li>如果本次提交不计入复习，例如 CE 且配置为不计入，则 ACK 后跳过；</li>
     *     <li>事务内认领事件幂等行（已 COMPLETED 则 ACK 跳过）；</li>
     *     <li>更新用户该题目的 {@link Review} 复习状态；</li>
     *     <li>同步更新当天 {@link ReviewRecord} 快照中的 pending/completed/ac 列表；</li>
     *     <li>事务内将幂等行置为 COMPLETED，随后 ACK。</li>
     * </ol>
     * </p>
     *
     * @param amqpMessage RabbitMQ 原始消息，包含消息体和 deliveryTag
     * @param channel RabbitMQ Channel，用于手动 ACK/NACK
     * @param routingKey 当前消息实际使用的路由键
     */
    @RabbitListener(queues = MqContexts.REVIEW_QUEUE_NAME)

    public void setReview(Message amqpMessage, Channel channel,
                          @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey){
        long tag=amqpMessage.getMessageProperties().getDeliveryTag();

        try{
             if(!MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY.equals(routingKey)){
                 log.warn("忽略未知复习消息 routingKey: {}", routingKey);
                 channel.basicAck(tag,false);
                 return;
             }
             ReviewJudgeRecordDto reviewJudgeRecordDto=EnvelopeCodec.unwrap(
                     new String(amqpMessage.getBody(), StandardCharsets.UTF_8), ReviewJudgeRecordDto.class);
             if(reviewJudgeRecordDto == null
                     || reviewJudgeRecordDto.getUserId() == null || reviewJudgeRecordDto.getQuestionId() == null
                     || reviewJudgeRecordDto.getJudgeRecordId() == null){
                 channel.basicNack(tag, false, false);
                 log.error("复习判题消息缺少必要字段");
                 return;
             }
             String eventId="review:judge:" + reviewJudgeRecordDto.getJudgeRecordId();
             ReviewConfig reviewConfig=getReviewConfig(reviewJudgeRecordDto.getUserId());
             if(Integer.valueOf(0).equals(reviewConfig.getEnableAutoReview())){
                 log.info("用户已关闭自动复习计划, userId: {}, questionId: {}",
                         reviewJudgeRecordDto.getUserId(), reviewJudgeRecordDto.getQuestionId());
                 channel.basicAck(tag,false);
                 return;
             }
             Integer q=getQuality(reviewJudgeRecordDto, reviewConfig);
              if(q.equals(-1)){
                  log.info("本次复习判题不计入复习计划, userId: {}, questionId: {}, status: {}",
                          reviewJudgeRecordDto.getUserId(), reviewJudgeRecordDto.getQuestionId(), reviewJudgeRecordDto.getStatus());
                  channel.basicAck(tag,false);
                  return;
              }
              processReviewWithLock(reviewJudgeRecordDto, q, reviewConfig, eventId);
              clearReviewRetry(amqpMessage);
              channel.basicAck(tag,false);

        } catch (Exception e){
            log.error(e.getMessage(),e);
             try {
                 String retryId = getReviewMessageRetryId(amqpMessage);
                 Long retryCount = redisTemplate.opsForHash().increment(
                         RedisContext.REVIEW_JUDGE_RETRY_COUNT_KEY, retryId, 1
                 );
                 boolean exhausted = retryCount >= 3;
                 if (exhausted) {
                     redisTemplate.opsForHash().put(
                             RedisContext.REVIEW_JUDGE_FAILED_KEY,
                             retryId,
                             new String(amqpMessage.getBody(), java.nio.charset.StandardCharsets.UTF_8)
                     );
                 }
                 channel.basicNack(tag,false,!exhausted);
             } catch (Exception nackException) {
                 log.error("复习判题消息失败处理异常", nackException);
             }

        }
    }

    private String getReviewMessageRetryId(Message message) {
        return DigestUtils.md5DigestAsHex(message.getBody());
    }

    private void clearReviewRetry(Message message) {
        redisTemplate.opsForHash().delete(
                RedisContext.REVIEW_JUDGE_RETRY_COUNT_KEY,
                getReviewMessageRetryId(message)
        );
    }

    /**
     * 使用用户当天快照对应的分布式锁，保护完整的读改写过程。
     * <p>
     * 事件幂等 claim 与 complete 都放在 {@code transactionTemplate.execute} 的回调内，
     * 与业务更新同事务提交/回滚：回滚时 claim 行一并消失（重投可重新接管），
     * 提交时幂等行与业务结果原子落库（不会出现业务已提交但幂等行仍是 PROCESSING）。
     * </p>
     */
    private void processReviewWithLock(ReviewJudgeRecordDto dto, Integer quality, ReviewConfig reviewConfig,
                                       String eventId)
            throws InterruptedException {
        String lockKey = "review:record:" + dto.getUserId() + ":" + LocalDate.now();
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = lock.tryLock(3, TimeUnit.SECONDS);
        if (!locked) {
            throw new IllegalStateException("复习记录正在更新，请稍后重试");
        }
        try {
            Boolean updated = transactionTemplate.execute(status -> updateReviewState(dto, quality, reviewConfig, eventId));
            if (Boolean.FALSE.equals(updated)) {
                log.info("事件已完成或复习题目已完成/不属于今日计划，跳过更新，eventId={}，userId={}，questionId={}",
                        eventId, dto.getUserId(), dto.getQuestionId());
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 在同一事务中更新 SM-2 状态和当天复习快照。
     * <p>
     * 事务体顺序：事件幂等 claim（DUPLICATE_COMPLETED 直接跳过）→ 快照成员判断 →
     * SM-2 更新 → 首次转掌握时经 Outbox 登记祝贺事件 → 今日快照更新 →
     * complete（与业务同提交）。快照成员判断与复习状态流转语义保持原样，
     * 作为 RECLAIM 重投场景下的第二层幂等兜底。
     * </p>
     * <p>
     * 复习掌握事件与 SM-2 更新同事务：状态由 学习中(0) 变为 已掌握(1) 与事件登记
     * 原子一致，事务回滚时两者一并撤销；提交后由 OutboxRelay 至少一次投递。
     * </p>
     */
    private boolean updateReviewState(ReviewJudgeRecordDto dto, Integer quality, ReviewConfig reviewConfig,
                                      String eventId) {
        ConsumedEventService.ClaimResult claimResult =
                consumedEventService.claim(eventId, MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY);
        if (claimResult == ConsumedEventService.ClaimResult.DUPLICATE_COMPLETED) {
            log.info("事件已消费完成，幂等跳过, eventId={}，userId={}，questionId={}",
                    eventId, dto.getUserId(), dto.getQuestionId());
            return false;
        }
        ReviewRecord todayRecord = reviewRecordMapper.getTodayRecord(dto.getUserId());
        if (todayRecord == null) {
            return false;
        }
        List<Long> pending = todayRecord.getPendingReviewQuestionIds() == null
                ? new ArrayList<>() : new ArrayList<>(todayRecord.getPendingReviewQuestionIds());
        List<Long> completed = todayRecord.getCompletedReviewQuestionIds() == null
                ? new ArrayList<>() : new ArrayList<>(todayRecord.getCompletedReviewQuestionIds());
        if (completed.contains(dto.getQuestionId()) || !pending.contains(dto.getQuestionId())) {
            return false;
        }

        Review review = reviewMapper.selectOne(new QueryWrapper<Review>()
                .eq("user_id", dto.getUserId())
                .eq("question_id", dto.getQuestionId()));
        if (review == null) {
            throw new IllegalArgumentException("未找到对应的复习记录");
        }
        // calculateNextReviewInterval 原地修改并返回同一对象，更新前的状态需先留底用于 0→1 判定
        Integer previousStatus = review.getStatus();
        Review calculated = calculateNextReviewInterval(review, quality, reviewConfig);
        if (reviewMapper.updateById(calculated) == 0) {
            throw new RuntimeException("更新复习记录失败");
        }
        publishMasteredIfFirstTransition(previousStatus, calculated);
        updateTodayReviewRecord(dto, todayRecord, pending, completed);
        consumedEventService.complete(eventId);
        return true;
    }

    /**
     * 复习计划首次由 学习中(0) 转为 已掌握(1) 时，经 Outbox 发布复习掌握祝贺事件。
     * <p>
     * 必须与 SM-2 更新同事务调用（当前调用点在 {@code updateReviewState} 事务体内、
     * {@code updateById} 成功之后）：状态变掌握与事件登记原子一致，事务回滚时
     * Outbox 行随业务一并消失；事务提交后由 OutboxRelay 至少一次投递，消费端
     * 以 messageId（{@code review:mastered:{userId}:{questionId}}，掌握是终态，
     * 天然唯一）幂等去重。题目名不在此填充，由消费端经 Feign 异步补齐。
     * </p>
     *
     * @param previousStatus SM-2 计算前的复习状态
     * @param calculated     SM-2 计算后的复习记录（已含最新 reviewCount/status）
     */
    private void publishMasteredIfFirstTransition(Integer previousStatus, Review calculated) {
        if (!Integer.valueOf(0).equals(previousStatus)
                || !Integer.valueOf(1).equals(calculated.getStatus())) {
            return;
        }
        ReviewMasteredDto masteredDto = ReviewMasteredDto.builder()
                .messageId("review:mastered:" + calculated.getUserId() + ":" + calculated.getQuestionId())
                .userId(calculated.getUserId())
                .questionId(calculated.getQuestionId())
                .masteredTime(LocalDateTime.now().format(MASTERED_TIME_FORMATTER))
                .joinTime(calculated.getCreateTime() == null
                        ? null : calculated.getCreateTime().format(MASTERED_TIME_FORMATTER))
                .totalReviewCount(calculated.getReviewCount() == null ? 0 : calculated.getReviewCount())
                .build();
        outboxPublisher.publish(EventTypes.REVIEW_MASTERED, masteredDto);
    }

    /**
     * 更新当天复习快照中的题目完成状态。
     * <p>
     * {@link ReviewRecord} 是用户当天第一次进入复习模块时生成的快照。
     * 因此这里不会把“不属于今日快照”的题目强行加入 completed 列表，
     * 否则会破坏“今日复习计划快照”的业务语义。
     * </p>
     * <p>
     * 更新规则：
     * <ul>
     *     <li>如果题目在 pending 中：从 pending 移除，并加入 completed；</li>
     *     <li>如果题目已在 completed 中：不重复加入；</li>
     *     <li>如果本次判题 AC：加入 acQuestionIds；</li>
     *     <li>如果题目既不在 pending 也不在 completed：只记录日志并跳过今日快照更新。</li>
     * </ul>
     * </p>
     *
     * @param reviewJudgeRecordDto 复习判题结果事件，包含 userId、questionId、status 等上下文
     */
    private void updateTodayReviewRecord(ReviewJudgeRecordDto reviewJudgeRecordDto,
                                         ReviewRecord todayRecord,
                                         List<Long> pendingQuestionIds,
                                         List<Long> completedQuestionIds) {
        Long questionId = reviewJudgeRecordDto.getQuestionId();
        List<Long> acQuestionIds = todayRecord.getAcQuestionIds() == null
                ? new ArrayList<>()
                : new ArrayList<>(todayRecord.getAcQuestionIds());

        pendingQuestionIds.remove(questionId);
        completedQuestionIds.add(questionId);
        if ("AC".equals(reviewJudgeRecordDto.getStatus()) && !acQuestionIds.contains(questionId)) {
            acQuestionIds.add(questionId);
        }

        todayRecord.setPendingReviewQuestionIds(pendingQuestionIds);
        todayRecord.setCompletedReviewQuestionIds(completedQuestionIds);
        todayRecord.setAcQuestionIds(acQuestionIds);

        int updateResult = reviewRecordMapper.updateById(todayRecord);
        if (updateResult == 0) {
            throw new RuntimeException("更新今日复习记录失败");
        }
    }

    /**
     * 将 OJ 判题结果转换为 SM-2 算法使用的质量评分 quality。
     * <p>
     * 映射规则：
     * <ul>
     *     <li>5：AC，全部通过；</li>
     *     <li>4：未 AC，但测试点通过率 >= 80%；</li>
     *     <li>3：测试点通过率 >= 60%；</li>
     *     <li>2：测试点通过率 >= 30%；</li>
     *     <li>1：测试点通过率 > 0；</li>
     *     <li>0：完全未通过，或 CE 且配置为计入复习；</li>
     *     <li>-1：本次提交不计入复习，例如 CE 且 countCompileError = 0。</li>
     * </ul>
     * </p>
     *
     * @param reviewJudgeRecordDto 复习判题结果事件
     * @param reviewConfig 用户复习配置，用于判断 CE 是否计入复习
     * @return SM-2 quality，范围通常为 0~5；-1 表示不计入本次复习
     */
    Integer getQuality(ReviewJudgeRecordDto reviewJudgeRecordDto, ReviewConfig reviewConfig) {

        if (reviewJudgeRecordDto == null) {
            return -1;
        }
        //系统内部原因不计入复习
        String errorMessage = reviewJudgeRecordDto.getErrorMessage();
        if(errorMessage != null && (errorMessage.contains("代码不能为空")
                || errorMessage.contains("不支持的语言")
                || errorMessage.contains("沙箱环境未就绪，请稍后重试"))){
            return -1;
        }
        String status = reviewJudgeRecordDto.getStatus();
        if (status == null) {
            return 0;
        }

        // AC 直接最高分
        if ("AC".equals(status)) {
            return 5;
        }

        // 编译错误是否计入复习，由用户配置决定
        if ("CE".equals(status)) {
            if (reviewConfig != null && Integer.valueOf(0).equals(reviewConfig.getCountCompileError())) {
                // 不计入本次复习
                return -1;
            }
            // 计入复习，按完全失败处理
            return 0;
        }

        Integer acTestTotal = reviewJudgeRecordDto.getAcTestTotal();
        Integer allTestTotal = reviewJudgeRecordDto.getAllTestTotal();

        // 防止空指针和除 0
        if (acTestTotal == null || allTestTotal == null || allTestTotal <= 0) {
            return 0;
        }

        // 防御一下异常数据
        if (acTestTotal < 0) {
            acTestTotal = 0;
        }
        if (acTestTotal > allTestTotal) {
            acTestTotal = allTestTotal;
        }

        BigDecimal passRate = BigDecimal.valueOf(acTestTotal)
                .divide(BigDecimal.valueOf(allTestTotal), 4, RoundingMode.HALF_UP);

        if (passRate.compareTo(new BigDecimal("0.80")) >= 0) {
            return 4;
        }
        if (passRate.compareTo(new BigDecimal("0.60")) >= 0) {
            return 3;
        }
        if (passRate.compareTo(new BigDecimal("0.30")) >= 0) {
            return 2;
        }
        if (passRate.compareTo(BigDecimal.ZERO) > 0) {
            return 1;
        }

        return 0;
    }





    /**
     * 使用默认 SM-2 参数计算下一次复习时间。
     *
     * @param review  用户某道题的复习状态记录
     * @param quality 本次复习质量评分，范围 0~5
     */
    public void calculateNextReviewInterval(Review review, Integer quality) {
        calculateNextReviewInterval(review, quality, null);
    }

    /**
     * 根据 SM-2 算法和用户配置更新复习间隔、难度因子、连续正确次数和下一次复习时间。
     * <p>
     * quality 建议由 OJ 判题结果换算：
     * <ul>
     *     <li>5：AC，全部通过；</li>
     *     <li>4：通过率 >= 80%，但未 AC；</li>
     *     <li>3：通过率 60% ~ 79%；</li>
     *     <li>2：通过率 30% ~ 59%；</li>
     *     <li>1：通过率 1% ~ 29%；</li>
     *     <li>0：通过率 0%，全 WA，或 CE。</li>
     * </ul>
     * </p>
     * <p>
     * SM-2 更新规则：
     * <ul>
     *     <li>quality &lt; 3：本次复习失败，连续正确次数清零，1 天后再复习；</li>
     *     <li>quality &gt;= 3：连续正确次数 +1；第 1 次间隔 1 天，第 2 次间隔 6 天；</li>
     *     <li>第 3 次及以后：interval = 上次 interval * EF；</li>
     *     <li>当新的 intervalDays 达到 masteredIntervalDays 时，标记为已掌握。</li>
     * </ul>
     * </p>
     *
     * @param review 用户某道题的复习状态记录
     * @param quality 本次复习质量评分，范围 0~5
     * @param reviewConfig 用户复习配置；为空时使用默认 EF 和掌握阈值
     * @return 已在内存中更新后的 Review 对象，调用方负责持久化
     * @throws IllegalArgumentException 当 review 为空或 quality 不在 0~5 范围内时抛出
     */
    public Review calculateNextReviewInterval(Review review, Integer quality, ReviewConfig reviewConfig) {
        if (review == null) {
            throw new IllegalArgumentException("复习记录不能为空");
        }
        if (quality == null || quality < 0 || quality > 5) {
            throw new IllegalArgumentException("quality 必须在 0 到 5 之间");
        }
        LocalDateTime now = LocalDateTime.now();
        BigDecimal initialEf = reviewConfig != null && reviewConfig.getInitialEasinessFactor() != null
                ? reviewConfig.getInitialEasinessFactor()
                : DEFAULT_EASINESS_FACTOR;
        BigDecimal minEf = reviewConfig != null && reviewConfig.getMinEasinessFactor() != null
                ? reviewConfig.getMinEasinessFactor()
                : EFLOW;
        Integer masteredIntervalDays = reviewConfig != null && reviewConfig.getMasteredIntervalDays() != null
                ? reviewConfig.getMasteredIntervalDays()
                : 30;

        BigDecimal oldEf = review.getEasinessFactor() == null ? initialEf : review.getEasinessFactor();
        BigDecimal newEf = calculateEasinessFactor(oldEf, quality, minEf);

        int oldRepetitions = review.getRepetitions() == null ? 0 : review.getRepetitions();
        int oldIntervalDays = review.getIntervalDays() == null ? 0 : review.getIntervalDays();

        int newRepetitions;
        int newIntervalDays;

        if (quality < 3) {
            newRepetitions = 0;
            newIntervalDays = 1;
        } else {
            newRepetitions = oldRepetitions + 1;
            if (newRepetitions == 1) {
                newIntervalDays = 1;
            } else if (newRepetitions == 2) {
                newIntervalDays = 6;
            } else {
                newIntervalDays = BigDecimal.valueOf(Math.max(oldIntervalDays, 1))
                        .multiply(newEf)
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValue();
            }
        }

        review.setEasinessFactor(newEf);
        review.setRepetitions(newRepetitions);
        review.setIntervalDays(newIntervalDays);
        review.setLastQuality(quality);
        review.setLastReviewTime(now);
        review.setNextReviewTime(now.plusDays(newIntervalDays));
        review.setReviewCount((review.getReviewCount() == null ? 0 : review.getReviewCount()) + 1);
        if(newIntervalDays >= masteredIntervalDays){
            review.setStatus(1);
        }
        review.setUpdateTime(now);

        return review;
    }

    /**
     * 使用默认最低 EF 计算新的难度因子。
     *
     * @param oldEf 当前难度因子
     * @param quality 本次复习质量评分
     * @return 调整后的难度因子，最低不低于默认下限 1.3
     */
    BigDecimal calculateEasinessFactor(BigDecimal oldEf, Integer quality) {
        return calculateEasinessFactor(oldEf, quality, EFLOW);
    }

    /**
     * 根据 SM-2 公式计算新的难度因子 EF。
     * <p>
     * 公式：EF' = EF + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))。
     * 新 EF 不允许低于用户配置的最低难度因子。
     * </p>
     *
     * @param oldEf 当前难度因子
     * @param quality 本次复习质量评分
     * @param minEf 最低难度因子下限
     * @return 调整后的难度因子
     */
    BigDecimal calculateEasinessFactor(BigDecimal oldEf, Integer quality, BigDecimal minEf) {
        BigDecimal qDiff = BigDecimal.valueOf(5 - quality);
        BigDecimal change = BigDecimal.valueOf(0.1)
                .subtract(qDiff.multiply(BigDecimal.valueOf(0.08).add(qDiff.multiply(BigDecimal.valueOf(0.02)))));
        BigDecimal newEf = oldEf.add(change).setScale(2, RoundingMode.HALF_UP);
        return newEf.compareTo(minEf) < 0 ? minEf : newEf;
    }

    /**
     * 获取指定用户的复习配置；如果不存在，则创建一份默认配置。
     * <p>
     * 该方法用于 HTTP 请求和 MQ 消费两种场景，因此不能依赖 {@link UserContext}，
     * 必须使用调用方传入的 userId。
     * </p>
     *
     * @param userId 用户 ID
     * @return 用户复习配置；不存在时返回新建的默认配置
     */
    private ReviewConfig getReviewConfig(Long userId) {

        ReviewConfig reviewConfig = reviewConfigMapper.selectOne(new QueryWrapper<ReviewConfig>().eq("user_id",userId));
        if(reviewConfig==null){
            reviewConfig=ReviewConfig.builder()
                    .userId(userId)
                    .reviewCount( Integer.MAX_VALUE)
                    .enableAutoReview( 1)
                    .countCompileError( 1 )
                    .minEasinessFactor( new BigDecimal("1.30"))
                    .initialEasinessFactor( new BigDecimal("2.50") )
                    .masteredIntervalDays( 30 )
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            reviewConfigMapper.insert(reviewConfig);
            return reviewConfig;
        }
        return reviewConfig;
    }
    /**
     * 获取用户所有的复习记录
     */
    public List<ReviewRecord>getAllRecord(){
        return reviewRecordMapper.selectList(new QueryWrapper<ReviewRecord>().eq("user_id",UserContext.getUserId()).orderByDesc("create_time"));
    }
    /**
     * 获取某一天的记录
     */
    public ReviewRecordVo getRecordById(Long id){
        ReviewRecord reviewRecord = reviewRecordMapper.selectById(id);
        if(reviewRecord==null){
            throw new IllegalArgumentException("未找到该日的复习计划");
        }
        ReviewRecordVo reviewRecordVo=new ReviewRecordVo(reviewRecord);
        List<Long>allQuestionIds=new ArrayList<>();
        allQuestionIds.addAll(reviewRecord.getPendingReviewQuestionIds());
        allQuestionIds.addAll(reviewRecord.getCompletedReviewQuestionIds());
        reviewRecordVo.setAllQuestionIds(allQuestionIds);
        Result<List<QuestionDto>>result= questionFeignClient.getFavorites(allQuestionIds);
        if(!result.getCode().equals(200)){
            throw new IllegalArgumentException("获取题目信息失败");
        }
        Map<Long,String>map=new HashMap<>();
        for(QuestionDto questionDto:result.getData()){
            map.put(questionDto.getQuestionId(),questionDto.getTitle());
        }
        reviewRecordVo.setAllQuestionTitles(map);
        return reviewRecordVo;
    }
    /**
     * 获取所有待复习的计划
      */
    public List<ReviewVo>getAllReview(){

        List<Review>reviews= reviewMapper.selectList(new QueryWrapper<Review>().eq("user_id",UserContext.getUserId()).orderByDesc("last_review_time"));
        List<Long> allQuestionIds =new ArrayList<>();
        for(Review review:reviews){
            allQuestionIds.add(review.getQuestionId());
        }
        Result<List<QuestionDto>> questionDtoResult=questionFeignClient.getFavorites(allQuestionIds);
        List<ReviewVo> reviewVoList=new ArrayList<>();
        if(!questionDtoResult.getCode().equals(200)){

            return ToReviewVoList(reviews);
        }
        Map<Long,Review>map=new HashMap<>();
        for(Review review:reviews){
            map.put(review.getQuestionId(),review);
        }

        for(QuestionDto questionDto:questionDtoResult.getData()){
            Review review= map.getOrDefault(questionDto.getQuestionId(),null);
            if(review==null){
                continue;
            }
            ReviewVo reviewVo=new ReviewVo(review);
            reviewVo.setQuestionTitle(questionDto.getTitle());
            reviewVoList.add(reviewVo);

        }
        return reviewVoList;
    }
    private List<ReviewVo>ToReviewVoList(List<Review> reviews){
        List<ReviewVo> reviewVoList=new ArrayList<>();
        for(Review review:reviews){
            ReviewVo reviewVo=new ReviewVo(review);
            reviewVoList.add(reviewVo);
        }
        return reviewVoList;
    }
    /**
     * 修改某题的复习权重或者是否暂停
     */
    public Review updateReview(Long reviewId, UpdateReviewDto updateReviewDto) {
        if(updateReviewDto==null){
            return null;
        }
        Review review = reviewMapper.selectById(reviewId);
        if(review==null){
            throw new IllegalArgumentException("未找到该题目的复习计划");
        }
        if(!review.getUserId().equals(UserContext.getUserId())){
            throw new IllegalArgumentException("无权操作他人复习记录");
        }
        ReviewRecord reviewRecord = reviewRecordMapper.getTodayRecord(UserContext.getUserId());
        if(reviewRecord!=null){
            if(reviewRecord.getPendingReviewQuestionIds().contains(review.getQuestionId())||reviewRecord.getCompletedReviewQuestionIds().contains(review.getQuestionId())){
                throw new IllegalArgumentException("该题目在今日复习计划暂不支持更改");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        if(updateReviewDto.getNextReviewData()!=null&&updateReviewDto.getNextReviewData().isAfter(now)){
            review.setNextReviewTime(updateReviewDto.getNextReviewData());
        }

        if(updateReviewDto.getStatus()!=null&&(updateReviewDto.getStatus()==0||updateReviewDto.getStatus()==2)){
            if(review.getStatus()==1){
                throw new IllegalArgumentException("该题目已掌握");
            }

            review.setStatus(updateReviewDto.getStatus());
        }else {
            throw new IllegalArgumentException("未知状态");
        }

        if(updateReviewDto.getWeight()!=null&&(updateReviewDto.getWeight() == 0 || updateReviewDto.getWeight() == 1)){
            review.setWeight(updateReviewDto.getWeight());
        }else {
            throw new IllegalArgumentException("未知权重");
        }

        int r=reviewMapper.updateById(review);
        if(r==0){
            throw new IllegalArgumentException("更新失败请稍后重试");
        }
        return review;

    }

    /**
     * 将某题移出复习计划
     * @param reviewId Long
     *
     */

    public void deleteReview(Long reviewId) {
        if(reviewId==null){
            throw new IllegalArgumentException("复习为空");
        }
        int r=reviewMapper.delete(new QueryWrapper<Review>().eq("review_id",reviewId).eq("user_id",UserContext.getUserId()));
        if(r==0){
            throw new IllegalArgumentException("删除失败");
        }

    }



    public ReviewRecordVo getReviewRecordByDay(LocalDate day) {
        if(day==null){
            throw new IllegalArgumentException("日期为空");
        }

        ReviewRecord record=reviewRecordMapper.selectOne(new QueryWrapper<ReviewRecord>().eq("review_date",day).eq("user_id",UserContext.getUserId()));
        if(record==null){
            throw new IllegalArgumentException("未找到当天复习计划");
        }
        ReviewRecordVo reviewRecordVo=new ReviewRecordVo(record);
        List<Long>allQuestionIds=new ArrayList<>();
        allQuestionIds.addAll(record.getPendingReviewQuestionIds());
        allQuestionIds.addAll(record.getCompletedReviewQuestionIds());
        reviewRecordVo.setAllQuestionIds(allQuestionIds);
        Result<List<QuestionDto>>result= questionFeignClient.getFavorites(allQuestionIds);
        if(!result.getCode().equals(200)){
            throw new IllegalArgumentException("获取题目信息失败");
        }
        Map<Long,String>map=new HashMap<>();
        for(QuestionDto questionDto:result.getData()){
            map.put(questionDto.getQuestionId(),questionDto.getTitle());
        }
        reviewRecordVo.setAllQuestionTitles(map);
        return reviewRecordVo;
    }



}
