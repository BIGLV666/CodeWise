package org.example.servicejudge.service;


import lombok.extern.slf4j.Slf4j;

import org.example.servicejudge.mapper.SubmitRecordMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;

import java.io.IOException;
@Slf4j
@Service
public class JudgeDeadQueueHandle  {
    @Autowired
    private SubmitRecordMapper submitRecordMapper;
    @RabbitListener(queues = "judge.dead.queue")
    public void consumeDeadMessage(
            Long submitRecord,
            @Header(required = false, name = "x-death") Object deathInfo) {

        submitRecordMapper.updateRecordToFailure(submitRecord);
        log.error("判题消息进入死信队列, 判题记录id={}, death={}", submitRecord, deathInfo);
    }
}
