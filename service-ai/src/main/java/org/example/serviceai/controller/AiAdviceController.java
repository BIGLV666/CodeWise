package org.example.serviceai.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.conversation.dto.AskDto;
import org.example.serviceai.conversation.dto.CursorPageResult;
import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.service.AdviceConversationService;
import org.example.serviceai.conversation.vo.HomeConversationVo;
import org.example.serviceai.dto.AiTaskDto;
import org.example.serviceai.entry.Message;
import org.example.serviceai.service.AiAdviceTaskService;
import org.example.serviceapi.dto.Result;
import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RestController
@RequestMapping("/api/ai/advice")
public class AiAdviceController {
    @Autowired
    private AdviceConversationService adviceConversationService;
    @Autowired
    private AiAdviceTaskService aiAdviceTaskService;
    @GetMapping
    @RateLimit(limit = 100, window = 60)
    public Result<List<HomeConversationVo>> getConversations(){
        return Result.success(adviceConversationService.getConversations());
    }
    @PostMapping(
            value = "/ask",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @RateLimit(limit = 20, window = 60)
    public SseEmitter ask(@RequestBody AskDto askDto) {
        Long userId = UserContext.getUserId();

        SseEmitter emitter = new SseEmitter(120_000L);
        // 本流 ASSISTANT 占位行主键（占位行落库后由回调回填），超时时按它精准取消
        AtomicReference<Long> assistantMessageId = new AtomicReference<>();

        CompletableFuture.runAsync(() -> {
            try {
                Message message =
                        adviceConversationService.askStream(
                                userId,
                                askDto.getConversationId(),
                                askDto.getQuestion(),
                                askDto.getCode(),
                                Role.USER,
                                askDto.getUserAiConfigId(),
                                askDto.getModelName(),
                                chunk -> {
                                    try {
                                        emitter.send(
                                                SseEmitter.event()
                                                        .name("chunk")
                                                        .data(chunk)
                                        );
                                    } catch (IOException e) {
                                        throw new RuntimeException(
                                                "SSE 客户端已断开",
                                                e
                                        );
                                    }
                                },
                                assistantMessageId::set
                        );

                emitter.send(
                        SseEmitter.event()
                                .name("answer")
                                .data(message)
                );

                emitter.send(
                        SseEmitter.event()
                                .name("done")
                                .data("[DONE]")
                );

                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send(
                            SseEmitter.event()
                                    .name("error")
                                    .data(toUserMessage(e))
                    );
                } catch (Exception ignored) {
                }

                emitter.complete();
            }
        });

        emitter.onTimeout(() -> {
            // 超时精准收尾：只取消本流的占位行（不影响同会话其他并发 SSE 流）；
            // 占位行尚未创建（capture 为 null）时不取消，后续 chunk 发送失败会自然走 CANCELLED
            Long messageId = assistantMessageId.get();
            if (messageId != null) {
                try {
                    adviceConversationService.cancelGeneratingMessage(messageId);
                } catch (Exception exception) {
                    log.warn("SSE 超时取消生成消息失败, messageId={}", messageId, exception);
                }
            }
            emitter.complete();
        });

        emitter.onError(throwable ->
                log.warn("SSE 连接异常退出, conversationId={}", askDto.getConversationId(), throwable));

        return emitter;
    }

    /**
     * 把生成异常映射为对用户安全的提示文案。
     *
     * <p>仅透出 {@link org.example.serviceai.service.AiProviderHttpException}
     * 的分类信息（认证/限流/HTTP 状态码）；其余异常（含 Provider 名称、内部
     * 根因、内部 URL）一律返回固定文案，避免内部细节泄漏到 SSE 载荷。</p>
     */
    String toUserMessage(Exception exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof org.example.serviceai.service.AiProviderHttpException providerException) {
                int statusCode = providerException.getStatusCode();
                if (statusCode == 401 || statusCode == 403) {
                    return "AI 服务认证失败，请检查 API Key 和模型权限";
                }
                if (statusCode == 429) {
                    return "AI 服务请求过于频繁或额度不足，请稍后重试";
                }
                return "AI 服务调用失败，HTTP " + statusCode;
            }
            current = current.getCause();
        }
        // 原始异常服务端留痕，客户端只见固定文案
        log.warn("SSE 生成失败（已脱敏）", exception);
        return "AI 服务暂时不可用，请稍后重试";
    }
    @GetMapping("/{conversationId}/messages")
    @RateLimit(limit = 100, window = 60)
    public Result<CursorPageResult<Message>> getAllMessage(
            @PathVariable Long conversationId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
      return Result.success( adviceConversationService.getMessages(
                UserContext.getUserId(), conversationId, cursor, size
        ));
    }

    @PostMapping("/task")
    @RateLimit(limit = 20, window = 60)
    public Result<String>aiTask(@RequestBody AiTaskDto taskDto) {
        aiAdviceTaskService.aiTask(taskDto);
        return Result.success("success");
    }
    @GetMapping("/task")
    @RateLimit(limit = 100, window = 60)
    public Result<List<Object>>getAllAdviceTasks(@RequestParam Long questionId) {
        return Result.success(aiAdviceTaskService.getAiAdvices(UserContext.getUserId(), questionId));
    }


}
