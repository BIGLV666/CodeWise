package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionServiceTest {

    private static final Long QUESTION_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SubmitRecordMapper submitRecordMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private UserFeignClient userFeignClient;
    @Mock
    private FunctionConfigMapper functionConfigMapper;

    @InjectMocks
    private QuestionService questionService;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void deleteQuestionRejectsNonOwnerNonAdmin() {
        UserContext.setUserId(OTHER_USER_ID);
        stubUser(OTHER_USER_ID, 1);
        stubQuestion(OWNER_ID);

        BizException ex = assertThrows(BizException.class,
                () -> questionService.deleteQuestion(QUESTION_ID));
        assertEquals(CommonErrorCode.NO_PERMISSION.getCode(), ex.getCode());
        verify(questionMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteQuestionAllowsOwner() {
        UserContext.setUserId(OWNER_ID);
        stubUser(OWNER_ID, 1);
        stubQuestion(OWNER_ID);
        when(questionMapper.deleteById(QUESTION_ID)).thenReturn(1);

        assertDoesNotThrow(() -> questionService.deleteQuestion(QUESTION_ID));
    }

    @Test
    void deleteQuestionAllowsAdminForOthersQuestion() {
        UserContext.setUserId(OTHER_USER_ID);
        stubUser(OTHER_USER_ID, 2);
        stubQuestion(OWNER_ID);
        when(questionMapper.deleteById(QUESTION_ID)).thenReturn(1);

        assertDoesNotThrow(() -> questionService.deleteQuestion(QUESTION_ID));
    }

    @Test
    void deleteQuestionRejectsMissingQuestion() {
        UserContext.setUserId(OWNER_ID);
        stubUser(OWNER_ID, 1);
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> questionService.deleteQuestion(QUESTION_ID));
        assertEquals(QuestionErrorCode.QUESTION_NOT_FOUND.getCode(), ex.getCode());
        verify(questionMapper, never()).deleteById(anyLong());
    }

    private void stubUser(Long userId, int roleId) {
        UserDto user = new UserDto();
        user.setUserId(userId.toString());
        user.setRoleId(roleId);
        user.setStatus(1);
        when(userFeignClient.getUserInfo(userId)).thenReturn(Result.success(user));
    }

    private void stubQuestion(Long ownerId) {
        when(questionMapper.selectById(QUESTION_ID))
                .thenReturn(Question.builder().questionId(QUESTION_ID).createUserId(ownerId).build());
    }
}
