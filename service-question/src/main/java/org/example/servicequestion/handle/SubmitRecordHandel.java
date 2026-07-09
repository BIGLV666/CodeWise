package org.example.servicequestion.handle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.JudgeResultDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.config.WebsocketContexts;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.entry.JudgeRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.JudgeRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.example.servicequestion.service.WebSocketPushService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
@Slf4j
public class SubmitRecordHandel implements MessageHandler {

    @Autowired
    private SubmitRecordMapper submitRecordMapper;

    @Autowired
    private QuestionMapper questionMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private WebSocketPushService webSocketPushService;
    @Autowired
    private JudgeRecordMapper judgeRecordMapper;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public String getRoutingKey() {
        return MqContexts.QUESTION_SUBMIT_RECORD_ROUTING_KEY;
    }

    @Override
    @Transactional
    public void handle(String messageBody, Channel channel, Message amqpMessage) {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        try {
            log.info("处理判题结果消息, deliveryTag: {}", deliveryTag);

            // 解析消息
            Long JudgeRecordId= objectMapper.readValue(messageBody,Long.class);

            JudgeRecord judgeRecord =judgeRecordMapper.selectById(JudgeRecordId);

            if (judgeRecord == null || judgeRecord.getSubmitRecordId() == null) {
                log.error("判题结果数据不完整");
                channel.basicAck(deliveryTag, false);  // 确认并丢弃
                return;
            }
            SubmitRecord submitRecord = submitRecordMapper.selectById(judgeRecord.getSubmitRecordId());
            if (submitRecord == null) {
                log.error("提交记录不存在: {}", judgeRecord.getSubmitRecordId());
                channel.basicAck(deliveryTag, false);
                return;
            }



            if(submitRecord.getJudgeStatus().equals("success")){
                log.error("重复消费: {}", judgeRecord.getSubmitRecordId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            JudgeResultDto judgeResultDto =JudgeResultDto.builder().submissionId(submitRecord.getSubmitRecordId()).language(submitRecord.getLanguage())
                    .code(submitRecord.getSubmitContent()).
                    submitStatus(judgeRecord.getSubmitStatus()).
                    failInde(judgeRecord.getFailIndex()).expectedOutput(judgeRecord.getExpectedOutput())
                    .actual(judgeRecord.getUserOutput()).timeUsed(judgeRecord.getTimeUsed()).
                    memoryUsed(judgeRecord.getMemoryUsed()).error(judgeRecord.getErrorMsg()).log(judgeRecord.getLog()).build();


            // 更新提交记录


            // 更新状态
            submitRecord.setSubmitStatus(judgeResultDto.getSubmitStatus());
            submitRecord.setJudgeStatus("success");
            submitRecord.setTimeUsed(judgeResultDto.getTimeUsed());
            submitRecord.setMemoryUsed(judgeResultDto.getMemoryUsed());

            int updateResult = submitRecordMapper.updateById(submitRecord);

            // 更新题目统计
            Long questionId = submitRecord.getQuestionId();
            int updateQuestion = questionMapper.updateTotal(questionId);

            int updateAc = 0;
            if ("AC".equals(judgeResultDto.getSubmitStatus())) {
                updateAc = questionMapper.updateTotalAc(questionId);
            }

            // 检查更新是否成功
            if (updateResult == 0 || updateQuestion == 0) {
                log.error("数据库更新失败");
                channel.basicNack(deliveryTag, false, true);  // 重新入队
                return;
            }
            //构建复习队列

            if(submitRecord.getSubmitScene().equals("REVIEW")){
                ReviewJudgeRecordDto reviewJudgeRecordDto=new ReviewJudgeRecordDto();
                reviewJudgeRecordDto.setUserId(submitRecord.getUserId());
                reviewJudgeRecordDto.setQuestionId(questionId);
                reviewJudgeRecordDto.setSubmitRecordId(submitRecord.getSubmitRecordId());
                reviewJudgeRecordDto.setJudgeRecordId(judgeRecord.getJudgeRecordId());
                reviewJudgeRecordDto.setStatus(judgeRecord.getSubmitStatus());
                reviewJudgeRecordDto.setErrorMessage(judgeRecord.getErrorMsg());
                reviewJudgeRecordDto.setAllTestTotal(judgeRecord.getTestTotal());
                reviewJudgeRecordDto.setAcTestTotal(judgeRecord.getFailIndex()-1);
                reviewJudgeRecordDto.setQuestionTitle(submitRecord.getQuestionTitle());
                rabbitTemplate.convertAndSend(
                        MqContexts.REVIEW_EXCHANGE,
                        MqContexts.REVIEW_JUDGE_RECORD_ROUTING_KEY,
                        reviewJudgeRecordDto
                );
            }

            // ★ 只确认一次，不批量确认
            channel.basicAck(deliveryTag, false);


            WebsocketSendDto websocketSendDto = new WebsocketSendDto();
            websocketSendDto.setQueueName(WebsocketContexts.JUDGE_RESULT);
            websocketSendDto.setUserId(submitRecord.getUserId());
            websocketSendDto.setResult(judgeResultDto);
            rabbitTemplate.convertAndSend(
                    MqContexts.MESSAGE_EXCHANGE,
                    MqContexts.WEBSOCKET_ROUTING_KEY,
                    websocketSendDto
            );



            log.info("判题结果处理成功, submissionId: {}", judgeResultDto.getSubmissionId());

        } catch (Exception e) {
            log.error("处理判题结果失败", e);
            try {
                // 拒绝并重新入队
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException ex) {
                log.error("NACK失败", ex);
            }
            // 抛出异常让事务回滚
            throw new RuntimeException("处理判题结果失败", e);
        }
    }
}