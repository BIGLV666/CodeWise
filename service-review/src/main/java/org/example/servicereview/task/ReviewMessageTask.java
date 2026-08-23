package org.example.servicereview.task;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.enums.ReminderType;
import org.example.serviceapi.dto.event.EventTypes;
import org.example.serviceapi.dto.notification.NotificationDto;
import org.example.servicecommon.config.MqContexts;
import org.example.serviceapi.dto.notification.NotificationReviewMqDto;
import org.example.servicecommon.outbox.OutboxService;
import org.example.servicereview.dto.ReviewReminderDto;
import org.example.servicereview.mapper.ReviewMapper;
import org.example.servicereview.mapper.ReviewRecordMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

/**
 * 复习提醒定时任务：早 10 点提醒未生成今日复习计划的用户，晚 21 点提醒今日计划未完成的用户。
 * <p>
 * 提醒消息不再裸直发 RabbitMQ（异常只记日志会导致提醒静默丢失），而是经事务性 Outbox
 * （{@code event_outbox} 表）登记后由 OutboxRelay 异步投递，达到至少一次语义；
 * messageId 形如 {@code review-reminder:{type}:{userId}:{yyyy-MM-dd}} 按天幂等，
 * 消费端（通知中心）以 messageId 幂等去重。每个用户一次小事务提交，避免整批单事务。
 * </p>
 */
@Async
@Component
@Slf4j
public class ReviewMessageTask {
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ReviewRecordMapper reviewRecordMapper;
    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RedissonClient redissonClient;
    private static final String REVIEW_REMINDER ="review-reminder";
    @Scheduled(cron = "0 0 10 * * *",zone = "Asia/Shanghai")
    public void NotRecordMessageTask()  {
        String lockKey="task:review-reminder:"+LocalDate.now()+":MORNING";
        RLock lock=redissonClient.getLock(lockKey);

            boolean isLock=lock.tryLock();
            try{
            if(!isLock){
                 return;
            }
            List<ReviewReminderDto>list=reviewMapper.getNotRecord();

                for(ReviewReminderDto r:list){
                    try {
                        String messageId = REVIEW_REMINDER+":" +"not_record"+":"+r.getUserId()+":"+ LocalDate.now();
                        NotificationDto notificationDto=new NotificationDto();
                        notificationDto.setMessageId(messageId);
                        notificationDto.setType(NotificationCenterType.REVIEW);
                        notificationDto.setBusinessType(BusinessType.REVIEW_RECORD);
                        notificationDto.setUserId(r.getUserId());
                        NotificationReviewMqDto  notificationReviewMqDto=new NotificationReviewMqDto();
                        notificationReviewMqDto.setReminderType(ReminderType.NOTRECORD);
                        notificationReviewMqDto.setTotal(r.getPendingCount());
                        notificationDto.setExtraData(objectMapper.writeValueAsString(notificationReviewMqDto));
                        publishReminder(notificationDto);

                }catch (Exception e){
                        log.error(e.getMessage());
                    }
            }
        }finally {
                if(lock.isHeldByCurrentThread()){
                    lock.unlock();
                }
            }
    }
    @Scheduled(cron = "0 0 21 * * *",zone = "Asia/Shanghai")
    public void haveRecordMessageTask()  {
        String lockKey="task:review-reminder:"+LocalDate.now()+"NIGHT";
        RLock lock=redissonClient.getLock(lockKey);
        try{
            boolean isLock=lock.tryLock();
            if(!isLock){
                return;
            }
            List<ReviewReminderDto>lists=reviewRecordMapper.getHaveRecord();
            for(ReviewReminderDto r:lists){
                try{
                    String messageId = REVIEW_REMINDER+":"+"have_record" +":"+r.getUserId()+":"+ LocalDate.now();
                    NotificationDto notificationDto=new NotificationDto();
                    notificationDto.setMessageId(messageId);
                    notificationDto.setType(NotificationCenterType.REVIEW);
                    notificationDto.setBusinessType(BusinessType.REVIEW_RECORD);
                    notificationDto.setUserId(r.getUserId());
                    NotificationReviewMqDto  notificationReviewMqDto=new NotificationReviewMqDto();
                    notificationReviewMqDto.setReminderType(ReminderType.HAVERECORD);
                    notificationReviewMqDto.setTotal(r.getPendingCount());
                    notificationDto.setExtraData(objectMapper.writeValueAsString(notificationReviewMqDto));
                    publishReminder(notificationDto);
                } catch (Exception e) {
                    log.error(e.getMessage());
                }
            }
        }finally {
            if(lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }
    }

    /**
     * 将单条提醒经事务性 Outbox 登记（每个用户一次小事务），由 OutboxRelay 异步投递。
     * <p>Outbox 写入要求事务上下文：登记行提交后至少会被投递一次，投递失败按指数退避重试、
     * 超限转 DEAD 留待人工核查，不再出现裸直发异常即静默丢失的情况。</p>
     */
    private void publishReminder(NotificationDto notificationDto) {
        transactionTemplate.executeWithoutResult(status -> outboxService.append(
                EventTypes.REVIEW_REMINDER,
                MqContexts.NOTIFICATION_EXCHANGE,
                MqContexts.NOTIFICATION_REVIEW_ROUTING_KEY,
                notificationDto));
    }
}
