package org.example.serviceai.conversation.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.serviceai.entry.Message;
import org.example.serviceai.conversation.enums.Role;
import org.example.serviceai.entry.MessageStatus;
import org.example.serviceai.mapper.ConversationMapper;
import org.example.serviceai.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仓储层收尾更新单元测试：状态收尾必须带 {@code WHERE status='GENERATING'}
 * 原子条件，已完成/已取消的行不被迟到回调覆盖。
 */
@ExtendWith(MockitoExtension.class)
class MybatisAiConversationRepositoryTest {

    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private MybatisAiConversationRepository repository;

    @BeforeAll
    static void initTableInfo() {
        // 离线初始化 MyBatis-Plus 表元数据，lambdaWrapper 才能解析列名
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, Message.class);
    }

    @Test
    void finishGeneratingMessageGuardedByGeneratingStatus() {
        when(messageMapper.update(isNull(), any())).thenReturn(1);

        boolean finished = repository.finishGeneratingMessage(9L, "answer", MessageStatus.COMPLETED);

        assertTrue(finished);
        // WHERE 段必须限定 status='GENERATING'（迟到回调不覆盖已完成/已取消行）
        AbstractWrapper<Message, ?, ?> wrapper = capturedUpdateWrapper();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("message_id ="), "sqlSegment=" + sqlSegment);
        assertTrue(sqlSegment.contains("status ="), "sqlSegment=" + sqlSegment);
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(9L), "params=" + params);
        assertTrue(params.containsValue(MessageStatus.GENERATING), "params=" + params);
        assertTrue(params.containsValue(MessageStatus.COMPLETED), "params=" + params);
        assertTrue(params.containsValue("answer"), "params=" + params);
    }

    @Test
    void finishGeneratingMessageReportsNoRowWhenAlreadyFinalized() {
        when(messageMapper.update(isNull(), any())).thenReturn(0);

        assertFalse(repository.finishGeneratingMessage(9L, "late", MessageStatus.COMPLETED));
    }

    @Test
    void cancelGeneratingMessagesScopedToConversationAndGeneratingStatus() {
        when(messageMapper.update(isNull(), any())).thenReturn(1);

        int cancelled = repository.cancelGeneratingMessages(5L);

        assertEquals(1, cancelled);
        AbstractWrapper<Message, ?, ?> wrapper = capturedUpdateWrapper();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("conversation_id ="), "sqlSegment=" + sqlSegment);
        assertTrue(sqlSegment.contains("status ="), "sqlSegment=" + sqlSegment);
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(5L), "params=" + params);
        assertTrue(params.containsValue(MessageStatus.GENERATING), "params=" + params);
        assertTrue(params.containsValue(MessageStatus.CANCELLED), "params=" + params);
    }

    @Test
    void getMessageDelegatesToSelectById() {
        Message message = Message.builder().messageId(9L).build();
        when(messageMapper.selectById(9L)).thenReturn(message);

        assertEquals(message, repository.getMessage(9L));
    }

    @Test
    void restartFailedGenerationResetsOnlyFailedRows() {
        when(messageMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(repository.restartFailedGeneration(9L));

        // WHERE status='FAILED' 原子守护：只有失败行能重置回生成中
        AbstractWrapper<Message, ?, ?> wrapper = capturedUpdateWrapper();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("message_id ="), "sqlSegment=" + sqlSegment);
        assertTrue(sqlSegment.contains("status ="), "sqlSegment=" + sqlSegment);
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(9L), "params=" + params);
        assertTrue(params.containsValue(MessageStatus.FAILED), "params=" + params);
        assertTrue(params.containsValue(MessageStatus.GENERATING), "params=" + params);
    }

    @Test
    void cancelGeneratingMessageScopedToSingleRow() {
        when(messageMapper.update(isNull(), any())).thenReturn(1);

        assertTrue(repository.cancelGeneratingMessage(9L));

        // 单消息取消：WHERE message_id=? AND status='GENERATING'，不触碰同会话其他行
        AbstractWrapper<Message, ?, ?> wrapper = capturedUpdateWrapper();
        String sqlSegment = wrapper.getSqlSegment();
        assertTrue(sqlSegment.contains("message_id ="), "sqlSegment=" + sqlSegment);
        assertTrue(sqlSegment.contains("status ="), "sqlSegment=" + sqlSegment);
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(9L), "params=" + params);
        assertTrue(params.containsValue(MessageStatus.GENERATING), "params=" + params);
        assertTrue(params.containsValue(MessageStatus.CANCELLED), "params=" + params);
    }

    @Test
    void existsMessageByRoleSinceQueriesByConversationRoleAndTime() {
        when(messageMapper.selectCount(any())).thenReturn(1L);
        LocalDateTime since = LocalDateTime.of(2026, 8, 23, 12, 0);

        assertTrue(repository.existsMessageByRoleSince(5L, Role.SYSTEM, since));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<AbstractWrapper> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(messageMapper).selectCount(captor.capture());
        AbstractWrapper<Message, ?, ?> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(5L), "params=" + params);
        assertTrue(params.containsValue(Role.SYSTEM), "params=" + params);
        assertTrue(params.containsValue(since), "params=" + params);
    }

    @Test
    void findLastMessageByRoleSinceDelegatesToSelectOne() {
        Message last = Message.builder().messageId(88L).build();
        when(messageMapper.selectOne(any())).thenReturn(last);
        LocalDateTime since = LocalDateTime.of(2026, 8, 23, 12, 0);

        Message found = repository.findLastMessageByRoleSince(5L, Role.ASSISTANT, since);

        assertEquals(last, found);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<AbstractWrapper> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(messageMapper).selectOne(captor.capture());
        AbstractWrapper<Message, ?, ?> wrapper = captor.getValue();
        wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        assertTrue(params.containsValue(5L), "params=" + params);
        assertTrue(params.containsValue(Role.ASSISTANT), "params=" + params);
        assertTrue(params.containsValue(since), "params=" + params);
    }

    /** 捕获最近一次 update(null, wrapper) 的更新 wrapper。 */
    @SuppressWarnings("rawtypes")
    private AbstractWrapper<Message, ?, ?> capturedUpdateWrapper() {
        ArgumentCaptor<AbstractWrapper> captor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(messageMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }
}
