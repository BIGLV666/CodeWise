package org.example.serviceai.conversation.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.conversation.dto.CursorPageResult;
import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.conversation.vo.HomeConversationVo;
import org.example.serviceai.entry.Conversation;
import org.example.serviceai.entry.AiConversationMemory;
import org.example.serviceai.entry.MessageStatus;

import org.example.serviceai.conversation.repository.AiConversationRepository;
import org.example.serviceai.entry.Message;
import org.example.serviceai.service.AIService;
import org.example.serviceai.service.UserAiService;
import org.example.servicecommon.until.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
@Slf4j
public class AdviceConversationService {
    @Autowired
    private AiConversationRepository aiConversationRepository;
    @Autowired
    private AIService aiService;
    @Autowired
    private AiConversationMemaryService  aiConversationMemaryService;
    @Autowired
    private UserAiService userAiService;
    /**
     * 同步追问（首次）：落一条用户/SYSTEM 消息并生成 AI 回答。
     *
     * @see #ask(Long, Long, String, String, Role, Long)
     */
    public Message ask(
            Long userId,
            Long conversationId,
            String question,
            String currentCode,
            Role type
    ) {
        return ask(userId, conversationId, question, currentCode, type, null);
    }

    /**
     * 同步追问（支持复用既有生成行）：reuseAssistantMessageId 为空时走首次链路
     * （落用户/SYSTEM 消息 + 新插 GENERATING 占位行）；非空时复用该行——先把
     * FAILED 行重置回 GENERATING（GENERATING 行原样复用），不再新增任何消息，
     * 再走原有 callAi → finishGeneratingMessage 收尾。用于 WA 建议事件重试时
     * 避免向会话重复刷追问消息与失败占位行。
     *
     * @param userId                 操作用户（须为会话归属人）
     * @param conversationId         会话 ID
     * @param question               追问内容
     * @param currentCode            当前代码
     * @param type                   追问消息角色（USER/SYSTEM）
     * @param reuseAssistantMessageId 复用的 ASSISTANT 生成行主键，null 表示首次
     * @return 已收尾（COMPLETED/FAILED 由异常体现）的建议消息
     * @throws IllegalStateException 会话不存在或无权操作（业务校验失败，走重试/死信而非毒消息）
     */
    public Message ask(
            Long userId,
            Long conversationId,
            String question,
            String currentCode,
            Role type,
            Long reuseAssistantMessageId
    ) {
        // 校验会话归属
        Conversation conversation = aiConversationRepository.get(conversationId);
        if(conversation ==null){
            throw new IllegalStateException("未找到该会话");
        }
        if(!conversation.getUserId().equals(userId)){
            throw new IllegalStateException("无权操作他人会话");
        }
        // 构建 prompt
        String prompt= AdvicePromptBuilder.buildFollowUp(
                conversation,
                aiConversationRepository.getRecentMessages(conversationId,6),
                question,
                currentCode,
                aiConversationMemaryService.getMemory(conversationId, userId)

        );
        Message savedMessage;
        if (reuseAssistantMessageId != null) {
            // 复用重试：FAILED 行重置回 GENERATING 后原行收尾，不新增追问消息
            aiConversationRepository.restartFailedGeneration(reuseAssistantMessageId);
            savedMessage = aiConversationRepository.getMessage(reuseAssistantMessageId);
            if (savedMessage == null) {
                throw new IllegalStateException("复用的生成行不存在: messageId=" + reuseAssistantMessageId);
            }
        } else {
            // 保存用户消息
            Message aiMessage = new Message();
            aiMessage.setConversationId(conversationId);
            aiMessage.setCurrentCode(currentCode);
            aiMessage.setRole(type);
            aiMessage.setUserId(userId);
            aiMessage.setContent(question);
            aiMessage.setCreateTime(LocalDateTime.now());
            aiConversationRepository.appendMessage(conversationId,aiMessage);
            // 调用 AI：先落 status=GENERATING 占位行（content 列 NOT NULL，先插空串），
            // 成功后回填 content 并置 COMPLETED，失败置 FAILED 后把异常抛给调用方
            Message message=new Message();
            message.setConversationId(conversationId);
            message.setContent("");
            message.setUserId(userId);
            message.setRole(Role.ASSISTANT);
            message.setCreateTime(LocalDateTime.now());
            message.setStatus(MessageStatus.GENERATING);
            savedMessage = aiConversationRepository.appendMessage(conversationId,message);
        }
        String repost;
        try {
            repost=aiService.callAi(prompt);
        } catch (Exception exception) {
            aiConversationRepository.finishGeneratingMessage(savedMessage.getMessageId(), "", MessageStatus.FAILED);
            throw exception;
        }
        // 空响应守卫：网关限流/流解析异常可能返回空串，按成功落库会出现空 COMPLETED 消息（前端空胶囊）；
        // 置 FAILED 并上抛，交分发器延迟重试或死信留痕
        if (repost == null || repost.isBlank()) {
            aiConversationRepository.finishGeneratingMessage(savedMessage.getMessageId(), "", MessageStatus.FAILED);
            throw new IllegalStateException("模型返回空响应");
        }
        aiConversationRepository.finishGeneratingMessage(savedMessage.getMessageId(), repost, MessageStatus.COMPLETED);
        savedMessage.setContent(repost);
        savedMessage.setStatus(MessageStatus.COMPLETED);
        updateMemoryAsync(conversationId, savedMessage.getUserId());
        return savedMessage;

    }

    public List<HomeConversationVo> getConversations(){

        return aiConversationRepository.findAllByUserId(UserContext.getUserId());
    }
    //游标分页
    public CursorPageResult<Message> getMessages(
            Long userId,
            Long conversationId,
            Long cursor,
            int size
    ) {
        Conversation conversation = aiConversationRepository.get(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权查看他人会话");
        }

        int pageSize = Math.max(1, Math.min(size, 50));
        List<Message> queried = aiConversationRepository.getMessagesBefore(
                conversationId, cursor, pageSize + 1
        );
        boolean hasMore = queried.size() > pageSize;
        List<Message> page = new ArrayList<>(
                hasMore ? queried.subList(0, pageSize) : queried
        );
        Long nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getMessageId();
        Collections.reverse(page);
        return new CursorPageResult<>(page, nextCursor, hasMore);
    }
    /**
     * SSE 流式追问（无占位行回调）：见
     * {@link #askStream(Long, Long, String, String, Role, Long, String, Consumer, Consumer)}。
     */
    public Message askStream(
            Long userId,
            Long conversationId,
            String question,
            String currentCode,
            Role role,
            Long userAiConfigId,
            String modelName,
            Consumer<String> onChunk
    ) {
        return askStream(userId, conversationId, question, currentCode, role,
                userAiConfigId, modelName, onChunk, null);
    }

    /**
     * SSE 流式追问（可选占位行回调）：先落 status=GENERATING 的 ASSISTANT 占位行，
     * 落库后经 onAssistantCreated 回调其 messageId（供调用方做超时精准取消等收尾），
     * 之后流式生成并原子收尾（完成 COMPLETED / 异常 FAILED / 客户端断开 CANCELLED）。
     *
     * @throws IllegalStateException 会话不存在或无权操作（业务校验失败，走重试/死信而非毒消息）
     * @throws IllegalArgumentException 自定义模型参数不全（客户端入参错误）
     */
    public Message askStream(
            Long userId,
            Long conversationId,
            String question,
            String currentCode,
            Role role,
            Long userAiConfigId,
            String modelName,
            Consumer<String> onChunk,
            Consumer<Long> onAssistantCreated
    ) {
        Conversation conversation =
                aiConversationRepository.get(conversationId);

        if (conversation == null) {
            throw new IllegalStateException("会话不存在");
        }

        if (!conversation.getUserId().equals(userId)) {
            throw new IllegalStateException("无权操作该会话");
        }

        String prompt = AdvicePromptBuilder.buildFollowUp(
                conversation,
                aiConversationRepository.getRecentMessages(
                        conversationId,
                        6
                ),
                question,
                currentCode,
                aiConversationMemaryService.getMemory(conversationId, userId)
        );

        Message userMessage = new Message();
        userMessage.setConversationId(conversationId);
        userMessage.setUserId(userId);
        userMessage.setRole(role);
        userMessage.setContent(question);
        userMessage.setCurrentCode(currentCode);
        userMessage.setCreateTime(LocalDateTime.now());

        aiConversationRepository.appendMessage(
                conversationId,
                userMessage
        );

        boolean useAutoProvider = userAiConfigId == null
                && (modelName == null || modelName.isBlank());
        if (!useAutoProvider
                && (userAiConfigId == null || modelName == null || modelName.isBlank())) {
            throw new IllegalArgumentException("自定义模型必须同时提供配置 ID 和模型名称");
        }

        // 流开始前先落 status=GENERATING 的 ASSISTANT 占位行（content 列 NOT NULL，先插空串），
        // 结束后原子收尾（WHERE status='GENERATING'）：完成→COMPLETED，异常→FAILED，
        // 客户端断开→CANCELLED，均保留已生成的部分内容
        Message assistantMessage = new Message();
        assistantMessage.setConversationId(conversationId);
        assistantMessage.setUserId(userId);
        assistantMessage.setRole(Role.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setCurrentCode(currentCode);
        assistantMessage.setCreateTime(LocalDateTime.now());
        assistantMessage.setStatus(MessageStatus.GENERATING);
        Message savedAssistant = aiConversationRepository.appendMessage(
                conversationId,
                assistantMessage
        );
        if (onAssistantCreated != null) {
            // 占位行已落库：把主键交给调用方（如 SSE 超时精准取消本条消息）
            onAssistantCreated.accept(savedAssistant.getMessageId());
        }

        StringBuilder fullAnswer = new StringBuilder();

        Consumer<String> answerConsumer = chunk -> {
            fullAnswer.append(chunk);
            onChunk.accept(chunk);
        };
        try {
            if (useAutoProvider) {
                aiService.streamAi(prompt, answerConsumer);
            } else {
                userAiService.streamAi(prompt, answerConsumer, userId, userAiConfigId, modelName);
            }
        } catch (Exception exception) {
            MessageStatus finalStatus = isClientDisconnect(exception)
                    ? MessageStatus.CANCELLED
                    : MessageStatus.FAILED;
            aiConversationRepository.finishGeneratingMessage(
                    savedAssistant.getMessageId(), fullAnswer.toString(), finalStatus);
            throw exception;
        }

        // 空响应守卫：同 ask()——空流按 FAILED 收尾并上抛重试，不落空 COMPLETED 行
        if (fullAnswer.toString().isBlank()) {
            aiConversationRepository.finishGeneratingMessage(
                    savedAssistant.getMessageId(), "", MessageStatus.FAILED);
            throw new IllegalStateException("模型返回空响应");
        }
        aiConversationRepository.finishGeneratingMessage(
                savedAssistant.getMessageId(), fullAnswer.toString(), MessageStatus.COMPLETED);
        savedAssistant.setContent(fullAnswer.toString());
        savedAssistant.setStatus(MessageStatus.COMPLETED);
        updateMemoryAsync(conversationId, savedAssistant.getUserId());
        return savedAssistant;
    }

    /**
     * 把会话内仍在生成（status=GENERATING）的 ASSISTANT 消息置为 CANCELLED，
     * 供 SSE 超时/断开时兜底收尾；已完成/已失败的行不受影响。
     *
     * @param conversationId 会话 ID
     * @return 本次取消的行数
     */
    public int cancelGeneratingMessages(Long conversationId) {
        return aiConversationRepository.cancelGeneratingMessages(conversationId);
    }

    /**
     * 单消息精准取消：仅当该行仍在生成（status='GENERATING'）时置为 CANCELLED，
     * 不影响同会话其他生成中的行（SSE 超时按 messageId 收尾，避免误杀并发流）。
     *
     * @param messageId ASSISTANT 占位行主键
     * @return 是否实际取消（行不存在或已收尾时为 false）
     */
    public boolean cancelGeneratingMessage(Long messageId) {
        return aiConversationRepository.cancelGeneratingMessage(messageId);
    }

    /**
     * 查找可复用的追问 ASSISTANT 生成行（WA 建议事件重试的事件级防重）。
     *
     * <p>规则：since（事件首次认领时间）之后该会话不存在 SYSTEM 消息 → 说明本事件
     * 尚未落过追问，返回 null；存在 → 返回 since 之后最后一条 ASSISTANT 消息的
     * messageId（无论其状态），由调用方按状态分派：COMPLETED → 只补发通知；
     * FAILED/GENERATING → 复用该行重试生成。</p>
     *
     * <p>边界：并发不同事件共用同一会话时，可能取到相邻事件落下的等价追问/建议
     * 并复用——属 at-least-once 语义下可接受的折中（宁可少生成一次，不重复刷消息）。</p>
     *
     * @param conversationId 会话 ID
     * @param since          事件首次认领时间（含）
     * @return 可复用的 ASSISTANT 行主键；本事件未落过追问时为 null
     */
    public Long findReusableFollowUpAssistant(Long conversationId, LocalDateTime since) {
        boolean followUpPlaced = aiConversationRepository.existsMessageByRoleSince(
                conversationId, Role.SYSTEM, since);
        if (!followUpPlaced) {
            return null;
        }
        Message last = aiConversationRepository.findLastMessageByRoleSince(
                conversationId, Role.ASSISTANT, since);
        return last == null ? null : last.getMessageId();
    }

    /** 判断异常链上是否含 IOException（SSE send 失败包装的「客户端已断开」）。 */
    private boolean isClientDisconnect(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.io.IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void updateMemoryAsync(Long conversationId, Long userId) {
        CompletableFuture.runAsync(() -> {
            try {
                aiConversationMemaryService.updateSummery(conversationId, userId);
            } catch (Exception exception) {
                log.error("Conversation memory update failed, conversationId={}, userId={}",
                        conversationId, userId, exception);
            }
        });
    }

}
