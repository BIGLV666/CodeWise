package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.QuestionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionPermissionServiceTest {

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private UserFeignClient userFeignClient;

    @Test
    void allowsQuestionOwner() {
        QuestionPermissionService service = serviceWithUser(10L, 1, 1);
        assertDoesNotThrow(() -> service.requireOwnerOrAdmin(1L, 10L));
    }

    @Test
    void allowsAdministrator() {
        QuestionPermissionService service = serviceWithUser(99L, 2, 1);
        assertDoesNotThrow(() -> service.requireOwnerOrAdmin(1L, 10L));
    }

    @Test
    void rejectsUnrelatedNormalUser() {
        QuestionPermissionService service = serviceWithUser(99L, 1, 1);
        BizException exception = assertThrows(BizException.class, () -> service.requireOwnerOrAdmin(1L, 10L));
        assertEquals(CommonErrorCode.NO_PERMISSION.getCode(), exception.getCode());
    }

    @Test
    void rejectsDisabledAdministrator() {
        QuestionPermissionService service = serviceWithUser(99L, 2, 0);
        BizException exception = assertThrows(BizException.class, () -> service.requireOwnerOrAdmin(1L, 10L));
        assertEquals(QuestionErrorCode.ACCOUNT_UNAVAILABLE.getCode(), exception.getCode());
    }

    private QuestionPermissionService serviceWithUser(Long ownerId, int roleId, int status) {
        Question question = Question.builder().questionId(1L).createUserId(ownerId).build();
        UserDto user = new UserDto();
        user.setRoleId(roleId);
        user.setStatus(status);
        when(questionMapper.selectById(1L)).thenReturn(question);
        when(userFeignClient.getUserInfo(10L)).thenReturn(Result.success(user));
        return new QuestionPermissionService(questionMapper, userFeignClient);
    }
}
