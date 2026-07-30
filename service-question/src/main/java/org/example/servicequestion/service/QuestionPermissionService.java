package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicequestion.entry.Question;
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
            throw new IllegalArgumentException("请登录后操作");
        }
        if (questionId == null) {
            throw new IllegalArgumentException("题目 ID 不能为空");
        }

        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new IllegalArgumentException("题目不存在");
        }

        Result<UserDto> userResult = userFeignClient.getUserInfo(userId);
        UserDto user = userResult == null ? null : userResult.getData();
        if (userResult == null || userResult.getCode() != 200 || user == null) {
            throw new IllegalStateException("用户信息校验失败");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new IllegalStateException("当前账号不可用");
        }
        if (!userId.equals(question.getCreateUserId())
                && !ADMIN_ROLE_ID.equals(user.getRoleId())) {
            throw new SecurityException("仅题目创建者或管理员可以生成测试用例");
        }
        return question;
    }

    public void requireAdmin(Long userId) {
        if (userId == null) {
            throw new SecurityException("请登录后操作");
        }
        Result<UserDto> userResult = userFeignClient.getUserInfo(userId);
        UserDto user = userResult == null ? null : userResult.getData();
        if (userResult == null || userResult.getCode() != 200 || user == null) {
            throw new IllegalStateException("用户信息校验失败");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new SecurityException("当前账号不可用");
        }
        if (!ADMIN_ROLE_ID.equals(user.getRoleId())) {
            throw new SecurityException("仅管理员可以使用 AI 对拍入库功能");
        }
    }
}
