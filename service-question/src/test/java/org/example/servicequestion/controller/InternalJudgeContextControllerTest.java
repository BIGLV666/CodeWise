package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.judge.JudgeContextDto;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.JudgeRecord;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.JudgeRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 内部判题上下文端点鉴权边界单测：
 * 无用户上下文（系统内部 Feign 调用）放行；携带用户上下文的非管理员拒绝。
 */
@ExtendWith(MockitoExtension.class)
class InternalJudgeContextControllerTest {

    @Mock
    private JudgeRecordMapper judgeRecordMapper;

    @Mock
    private SubmitRecordMapper submitRecordMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private UserFeignClient userFeignClient;

    @InjectMocks
    private InternalJudgeContextController controller;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void allowsSystemInternalCallWithoutUserContext() {
        // 不设置 UserContext，模拟 MQ 消费线程经 Feign 透传（无用户头）
        stubFullContext();

        Result<JudgeContextDto> result = controller.getJudgeContext(123L);

        assertEquals(200, result.getCode());
        JudgeContextDto context = result.getData();
        assertNotNull(context);
        assertEquals(123L, context.getJudgeRecordId());
        assertEquals(555L, context.getSubmitRecordId());
        assertEquals(9L, context.getQuestionId());
        assertEquals("print(1)", context.getCode());
        assertEquals("WA", context.getJudgeStatus());
        assertEquals("题目描述", context.getQuestionContent());
    }

    @Test
    void allowsAdministratorWithUserContext() {
        UserContext.setUserId(7L);
        stubUserRole(7L, 2);
        stubFullContext();

        Result<JudgeContextDto> result = controller.getJudgeContext(123L);

        assertEquals(200, result.getCode());
    }

    @Test
    void rejectsNonAdminUser() {
        UserContext.setUserId(7L);
        stubUserRole(7L, 1);

        assertThrows(IllegalArgumentException.class, () -> controller.getJudgeContext(123L));

        // 非管理员请求不应触达任何业务数据查询（防 IDOR）
        verify(judgeRecordMapper, never()).selectById(anyLong());
        verify(submitRecordMapper, never()).selectById(anyLong());
        verify(questionMapper, never()).selectById(anyLong());
    }

    @Test
    void rejectsWhenUserInfoUnavailable() {
        UserContext.setUserId(7L);
        when(userFeignClient.getUserInfo(7L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> controller.getJudgeContext(123L));
        verify(judgeRecordMapper, never()).selectById(any());
    }

    private void stubUserRole(Long userId, int roleId) {
        UserDto user = new UserDto();
        user.setRoleId(roleId);
        when(userFeignClient.getUserInfo(userId)).thenReturn(Result.success(user));
    }

    private void stubFullContext() {
        JudgeRecord judgeRecord = new JudgeRecord();
        judgeRecord.setJudgeRecordId(123L);
        judgeRecord.setSubmitRecordId(555L);
        judgeRecord.setSubmitStatus("WA");
        judgeRecord.setCode("print(1)");
        when(judgeRecordMapper.selectById(123L)).thenReturn(judgeRecord);

        SubmitRecord submitRecord = new SubmitRecord();
        submitRecord.setSubmitRecordId(555L);
        submitRecord.setQuestionId(9L);
        submitRecord.setLanguage("Python");
        when(submitRecordMapper.selectById(555L)).thenReturn(submitRecord);

        Question question = new Question();
        question.setQuestionId(9L);
        question.setDescription("题目描述");
        when(questionMapper.selectById(9L)).thenReturn(question);
    }
}
