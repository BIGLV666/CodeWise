package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.QuestionMapper;
import org.springframework.stereotype.Service;

@Service
public class QuestionPermissionService {

    private static final Integer ADMIN_ROLE_ID = 2;

    private final QuestionMapper questionMapper;
    private final UserFeignClient userFeignClient;

    public QuestionPermissionService(
            QuestionMapper questionMapper,
            UserFeignClient userFeignClient
    ) {
        this.questionMapper = questionMapper;
        this.userFeignClient = userFeignClient;
    }

    public Question requireOwnerOrAdmin(Long questionId, Long userId) {
        if (userId == null) {
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        if (questionId == null) {
            throw new BizException(QuestionErrorCode.QUESTION_ID_REQUIRED);
        }

        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new BizException(QuestionErrorCode.QUESTION_NOT_FOUND);
        }

        Result<UserDto> userResult = userFeignClient.getUserInfo(userId);
        UserDto user = userResult == null ? null : userResult.getData();
        if (userResult == null || userResult.getCode() != 200 || user == null) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(QuestionErrorCode.ACCOUNT_UNAVAILABLE);
        }
        if (!userId.equals(question.getCreateUserId())
                && !ADMIN_ROLE_ID.equals(user.getRoleId())) {
            throw new BizException(CommonErrorCode.NO_PERMISSION);
        }
        return question;
    }

    public void requireAdmin(Long userId) {
        if (userId == null) {
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        Result<UserDto> userResult = userFeignClient.getUserInfo(userId);
        UserDto user = userResult == null ? null : userResult.getData();
        if (userResult == null || userResult.getCode() != 200 || user == null) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(QuestionErrorCode.ACCOUNT_UNAVAILABLE);
        }
        if (!ADMIN_ROLE_ID.equals(user.getRoleId())) {
            throw new BizException(CommonErrorCode.ADMIN_ONLY);
        }
    }
}
