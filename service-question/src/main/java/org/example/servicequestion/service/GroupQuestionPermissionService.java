package org.example.servicequestion.service;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.feign.GroupFeignClient;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 私密题目访问权限：创建者 / 小组 APPROVED 成员 / 平台管理员。
 *
 * <p>小组题目正文在 service-question，归属关系在 service-quiz-nest，因此私密题必须
 * 回调 quiz-nest 判断成员关系。本类集中处理三件事：</p>
 * <ol>
 *   <li>把 quiz-nest 的「不是组员 / 题目不在小组 / 无权操作」翻译成普通拒绝，
 *       不能包装成 500「下游服务不可用」；</li>
 *   <li>把真正的 Feign 异常、null 响应、未知错误码统一映射为
 *       {@link CommonErrorCode#DOWNSTREAM_UNAVAILABLE}；</li>
 *   <li>保留历史语义：创建者与平台管理员（roleId=0/2）始终可以访问自己的/任意私密题。</li>
 * </ol>
 */
@Slf4j
@Service
public class GroupQuestionPermissionService {

    /** quiz-nest：当前用户不是该小组成员。 */
    private static final int QUIZ_NOT_GROUP_MEMBER = 4041101;
    /** quiz-nest：题目不在任何小组索引中（非小组题，或索引已清理）。 */
    private static final int QUIZ_GROUP_QUESTION_NOT_FOUND = 4041104;
    /** quiz-nest：当前用户没有小组管理员权限。 */
    private static final int QUIZ_NO_GROUP_PERMISSION = 4031103;

    /** 平台管理员角色：0=root，2=管理员；1=普通用户。 */
    private static final int ROLE_ROOT = 0;
    private static final int ROLE_ADMIN = 2;

    private final GroupFeignClient groupFeignClient;
    private final UserFeignClient userFeignClient;
    private final String internalToken;

    public GroupQuestionPermissionService(
            GroupFeignClient groupFeignClient,
            UserFeignClient userFeignClient,
            @Value("${codewise.internal-token}") String internalToken) {
        this.groupFeignClient = groupFeignClient;
        this.userFeignClient = userFeignClient;
        this.internalToken = internalToken;
    }

    /**
     * 判断当前用户能否访问一道私密题（status=3）。
     *
     * <p>调用方必须先确认题目存在且状态为 3；本方法负责创建者、组员、管理员三条放行路径。</p>
     *
     * @param question 私密题目
     * @return true 表示允许访问；false 表示普通拒绝（调用方决定返回 403 还是 404）
     * @throws BizException 未登录或下游服务异常时抛出
     */
    public boolean canAccessPrivateQuestion(Question question) {
        if (question == null || question.getQuestionId() == null) {
            throw new BizException(QuestionErrorCode.QUESTION_ID_REQUIRED);
        }
        Long userId = requireLogin();
        // 创建者始终可以访问自己的私密题（不依赖小组索引）
        if (Objects.equals(question.getCreateUserId(), userId)) {
            return true;
        }

        Boolean groupMember = queryGroupMembership(question.getQuestionId());
        if (Boolean.TRUE.equals(groupMember)) {
            return true;
        }
        // null：题目不在任何小组（例如用户自己的私密题），历史语义允许平台管理员访问
        if (groupMember == null && isPlatformAdmin(userId)) {
            return true;
        }
        return false;
    }

    /**
     * 判断当前用户能否维护题目样例。
     *
     * <p>私密题（小组题）要求组内 ROOT/ADMIN；非私密题要求平台管理员（roleId=0/2）。
     * 与读取权限的区别是：平台管理员也不能改小组私有题的样例。</p>
     *
     * @param question 题目
     * @return true 表示允许；false 表示普通拒绝
     * @throws BizException 未登录或下游服务异常时抛出
     */
    public boolean canMaintainQuestionSamples(Question question) {
        if (question == null || question.getQuestionId() == null) {
            throw new BizException(QuestionErrorCode.QUESTION_ID_REQUIRED);
        }
        if (Integer.valueOf(3).equals(question.getStatus())) {
            return queryGroupAdmin(question.getQuestionId());
        }
        return isPlatformAdmin(requireLogin());
    }

    /** 内部调用必须带用户身份；没有身份时直接拒绝。 */
    private Long requireLogin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(CommonErrorCode.LOGIN_REQUIRED);
        }
        return userId;
    }

    /**
     * 查询 quiz-nest 的小组成员关系。
     *
     * @return true=是组员；false=不是组员（已明确拒绝）；null=题目不在任何小组
     * @throws BizException 下游异常、响应结构不完整或未知错误码
     */
    private Boolean queryGroupMembership(Long questionId) {
        Result<Boolean> body;
        try {
            body = groupFeignClient.checkIsGroupMember(internalToken, questionId);
        } catch (Exception e) {
            log.error("查询小组题目权限失败: questionId={}", questionId, e);
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, e);
        }
        if (body == null || body.getCode() == null) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }

        int code = body.getCode();
        if (code == 200) {
            if (body.getData() == null) {
                throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
            }
            return body.getData();
        }
        if (code == QUIZ_NOT_GROUP_MEMBER || code == QUIZ_NO_GROUP_PERMISSION) {
            return false;
        }
        if (code == QUIZ_GROUP_QUESTION_NOT_FOUND) {
            return null;
        }
        log.error("查询小组题目权限返回异常: questionId={}, code={}, message={}",
                questionId, code, body.getMessage());
        throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
    }

    /**
     * 查询用户是否为小组管理员。
     *
     * @return true=组内 ROOT/ADMIN；false=明确无权限（非组员、题目不在小组、平台题）
     * @throws BizException 下游异常、响应结构不完整或未知错误码
     */
    private boolean queryGroupAdmin(Long questionId) {
        Result<Boolean> body;
        try {
            body = groupFeignClient.checkIsGroupAdmin(internalToken, questionId);
        } catch (Exception e) {
            log.error("查询小组题目维护权限失败: questionId={}", questionId, e);
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, e);
        }
        if (body == null || body.getCode() == null) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }

        int code = body.getCode();
        if (code == 200) {
            if (body.getData() == null) {
                throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
            }
            return body.getData();
        }
        if (code == QUIZ_NOT_GROUP_MEMBER
                || code == QUIZ_GROUP_QUESTION_NOT_FOUND
                || code == QUIZ_NO_GROUP_PERMISSION) {
            return false;
        }
        log.error("查询小组题目维护权限返回异常: questionId={}, code={}, message={}",
                questionId, code, body.getMessage());
        throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
    }

    /**
     * 平台管理员判定：只有 roleId=0（root）或 2（管理员）放行。
     *
     * <p>用户服务不可用时必须按下游异常失败，不能把管理员误判成普通用户而误拒。</p>
     */
    private boolean isPlatformAdmin(Long userId) {
        Result<UserDto> body;
        try {
            body = userFeignClient.getUserInfo(userId);
        } catch (Exception e) {
            log.error("查询用户角色失败: userId={}", userId, e);
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE, e);
        }
        if (body == null || body.getCode() == null || body.getCode() != 200) {
            throw new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (body.getData() == null) {
            throw new BizException(QuestionErrorCode.USER_NOT_FOUND);
        }
        Integer roleId = body.getData().getRoleId();
        return Integer.valueOf(ROLE_ROOT).equals(roleId)
                || Integer.valueOf(ROLE_ADMIN).equals(roleId);
    }
}
