package org.example.serviceai.handle.testcasehandle;

import org.example.serviceai.MQ.Mq;
import org.example.serviceai.serviceQuestion.TestCaseBuilder;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.config.MqContexts;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.event.EventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GLM 用例生成处理器单元测试：终态失败（重试超限）才发「删除题目」补偿，
 * 未超限失败不补偿、成功不补偿。
 */
@ExtendWith(MockitoExtension.class)
class GLMTestCaseHandleTest {

    @Mock
    private TestCaseBuilder testCaseBuilder;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private QuestionFeignClient questionFeignClient;

    @InjectMocks
    private GLMTestCaseHandle handler;

    private static final String BODY =
            "{\"questionId\":20,\"title\":\"题目\",\"createUserId\":10}";

    @Test
    void terminalFailureSendsDeleteCompensation() throws Exception {
        when(questionFeignClient.getQuestionInfo(20L)).thenThrow(new RuntimeException("feign down"));

        assertThrows(RuntimeException.class,
                () -> handler.handle(BODY, amqpMessageOf(Mq.MAX_RETRY_COUNT)));

        // 重试超限：本次失败后即死信，删除题目补偿只发这一次
        verify(rabbitTemplate).convertAndSend(
                eq(MqContexts.Question_EXCHANGE),
                eq(MqContexts.QUESTION_DELETE_QUESTION_ROUTING_KEY),
                any(QuestionMessage.class));
    }

    @Test
    void nonTerminalFailureSkipsCompensation() throws Exception {
        when(questionFeignClient.getQuestionInfo(20L)).thenThrow(new RuntimeException("feign down"));

        // 无 retry 头（首次尝试）：交分发器延迟重试，不补偿（题目尚不能删）
        assertThrows(RuntimeException.class, () -> handler.handle(BODY, amqpMessageOf(null)));

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void alreadyGeneratedCaseSucceedsWithoutAnyCompensation() throws Exception {
        org.example.serviceapi.dto.Result<org.example.serviceapi.dto.question.QuestionDto> result =
                org.example.serviceapi.dto.Result.success(
                        org.example.serviceapi.dto.question.QuestionDto.builder()
                                .aiStatue("success")
                                .build());
        when(questionFeignClient.getQuestionInfo(20L)).thenReturn(result);

        handler.handle(BODY, amqpMessageOf(null));

        verifyNoInteractions(rabbitTemplate, testCaseBuilder);
    }

    private Message amqpMessageOf(Integer retryCount) {
        MessageProperties properties = new MessageProperties();
        if (retryCount != null) {
            properties.setHeader(EventPublisher.HEADER_RETRY_COUNT, retryCount);
        }
        properties.setDeliveryTag(7L);
        return new Message(BODY.getBytes(StandardCharsets.UTF_8), properties);
    }
}
