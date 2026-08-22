package org.example.servicequestion.handle;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.judge.JudgeResultDto;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.config.WebsocketContexts;
import org.example.servicecommon.dto.ReviewJudgeRecordDto;
import org.example.servicecommon.dto.WebsocketSendDto;
import org.example.servicecommon.event.EnvelopeCodec;
import org.example.servicequestion.MQ.MessageHandler;
import org.example.servicequestion.entry.JudgeRecord;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.JudgeRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Component
@Slf4j
public class SubmitRecordHandel implements MessageHandler {

    /**
     * WebSocket 推送大字段截断上限（字符数）。
     *
     * <p>截断只作用于发往 MQ -> WebSocket 的 DTO 副本：数据库写入
     * （submit_record 状态更新）与判题结果比较均使用原始完整数据，
     * 不受截断影响；error 为编译/系统错误诊断信息，不参与截断。</p>
     */
    static final int MAX_FIELD_LENGTH = 16 * 1024;

    /** 截断标记后缀，提示前端内容已裁剪 */
    private static final String TRUNCATED_SUFFIX = "...[truncated]";

    @Autowired
    private SubmitRecordMapper submitRecordMapper;

    @Autowired
    private QuestionMapper questionMapper;

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

            // 解析消息：信封 / 裸 Long 双读（灰度期间新旧格式共存）
            Long JudgeRecordId = EnvelopeCodec.unwrap(messageBody, Long.class);

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
            // 推送给前端的结果使用截断副本，避免超大 code/log/输出撑爆 WS 消息
            websocketSendDto.setResult(truncateForPush(judgeResultDto));
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

    /**
     * 构建发往 WebSocket 的判题结果副本，对 code/log/expectedOutput/actual
     * 四个大字段做长度上限截断（error 不截）。
     *
     * <p>边界说明：截断只影响本方法返回的 MQ 推送副本；数据库写入
     * （submit_record 状态/耗时/内存更新）与判题比较发生在截断之前，
     * 始终使用 judgeRecord/submitRecord 的原始完整数据。</p>
     */
    private JudgeResultDto truncateForPush(JudgeResultDto source) {
        return JudgeResultDto.builder()
                .submissionId(source.getSubmissionId())
                .language(source.getLanguage())
                .code(truncate(source.getCode()))
                .submitStatus(source.getSubmitStatus())
                .failInde(source.getFailInde())
                .expectedOutput(truncate(source.getExpectedOutput()))
                .actual(truncate(source.getActual()))
                .timeUsed(source.getTimeUsed())
                .memoryUsed(source.getMemoryUsed())
                .error(source.getError())
                .log(truncate(source.getLog()))
                .build();
    }

    /**
     * 单字段截断：超出上限时截断并附加标记后缀（总长不超过上限）。
     */
    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_FIELD_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_FIELD_LENGTH - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }
}
