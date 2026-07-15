package org.example.servicereview.task;


import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.enums.BusinessType;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.serviceapi.enums.ReminderType;
import org.example.serviceapi.mqMessages.NotificationDto;
import org.example.servicecommon.config.MqContexts;
import org.example.serviceapi.dto.NotificationReviewMqDto;
import org.example.servicereview.dto.ReviewReminderDto;
import org.example.servicereview.mapper.ReviewMapper;
import org.example.servicereview.mapper.ReviewRecordMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Async
@Component
@Slf4j
public class ReviewMessageTask {
    @Autowired
    private RabbitTemplate rabbitTemplate;
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
                        rabbitTemplate.convertAndSend(
                                MqContexts.NOTIFICATION_EXCHANGE,
                                MqContexts.NOTIFICATION_REVIEW_ROUTING_KEY,
                                notificationDto);

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
                    rabbitTemplate.convertAndSend(
                            MqContexts.NOTIFICATION_EXCHANGE,
                            MqContexts.NOTIFICATION_REVIEW_ROUTING_KEY,
                            notificationDto);
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
}
