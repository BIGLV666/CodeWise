package org.example.serviceai.controller;

import org.example.serviceai.conversation.dto.AskDto;
import org.example.serviceai.conversation.dto.CursorPageResult;
import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.service.AdviceConversationService;
import org.example.serviceai.conversation.vo.HomeConversationVo;
import org.example.serviceai.entry.Message;
import org.example.serviceapi.dto.Result;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/ai/advice")
public class AiAdviceController {
    @Autowired
    private AdviceConversationService adviceConversationService;
    @GetMapping
    public Result<List<HomeConversationVo>> getConversations(){
        return Result.success(adviceConversationService.getConversations());
    }
    @PostMapping(
            value = "/ask",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter ask(@RequestBody AskDto askDto) {
        Long userId = UserContext.getUserId();

        SseEmitter emitter = new SseEmitter(120_000L);

        CompletableFuture.runAsync(() -> {
            try {
                Message message =
                        adviceConversationService.askStream(
                                userId,
                                askDto.getConversationId(),
                                askDto.getQuestion(),
                                askDto.getCode(),
                                Role.USER,
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
                                }
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
                                    .data(e.getMessage())
                    );
                } catch (Exception ignored) {
                }

                emitter.complete();
            }
        });

        emitter.onTimeout(emitter::complete);

        return emitter;
    }
    @GetMapping("/{conversationId}/messages")
    public Result<CursorPageResult<Message>> getAllMessage(
            @PathVariable Long conversationId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size
    ) {
      return Result.success( adviceConversationService.getMessages(
                UserContext.getUserId(), conversationId, cursor, size
        ));
    }

}
