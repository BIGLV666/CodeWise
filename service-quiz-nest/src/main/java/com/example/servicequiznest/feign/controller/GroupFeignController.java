package com.example.servicequiznest.feign.controller;

import com.example.servicequiznest.exception.QuizNestErrorCode;
import com.example.servicequiznest.feign.services.GroupFeignService;
import io.github.biglv666.apigovernance.annotation.Skip;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小组内部接口：供 service-question 经 {@code service-api} 的
 * {@link org.example.serviceapi.feign.GroupFeignClient} 调用。
 *
 * <p>鉴权分两层：{@code X-Internal-Token} 由公共拦截器校验；额外的
 * {@code X-CodeWise-Internal-Token} 只由服务间显式传递，网关不会注入，
 * 因此外部请求即使能访问网关也无法伪造内部调用。</p>
 */
@RestController
@RequestMapping("/api/quiz/internal")
public class GroupFeignController {

    private final GroupFeignService groupFeignService;
    private final String internalToken;

    public GroupFeignController(
            GroupFeignService groupFeignService,
            @Value("${codewise.internal-token}") String internalToken) {
        this.groupFeignService = groupFeignService;
        this.internalToken = internalToken;
    }

    /**
     * 判断当前用户能否读取指定题目（小组自建题仅组员/平台管理员可读）。
     */
    @GetMapping("/questions/{questionId}/member")
    @Skip(reason = "系统内部端点（service-question 校验小组题目可见性经 Feign 调用），鉴权依赖 X-CodeWise-Internal-Token 校验，不参与限流")
    public Result<Boolean> checkIsGroupMember(
            @RequestHeader("X-CodeWise-Internal-Token") String requestToken,
            @PathVariable Long questionId) {
        checkInternalToken(requestToken);
        return Result.success(groupFeignService.checkIsGroupMember(questionId));
    }

    /**
     * 判断当前用户能否维护指定题目的样例（小组自建题仅组 ROOT/ADMIN）。
     */
    @GetMapping("/questions/{questionId}/admin")
    @Skip(reason = "系统内部端点（service-question 校验小组题目维护权限经 Feign 调用），鉴权依赖 X-CodeWise-Internal-Token 校验，不参与限流")
    public Result<Boolean> checkIsGroupAdmin(
            @RequestHeader("X-CodeWise-Internal-Token") String requestToken,
            @PathVariable Long questionId) {
        checkInternalToken(requestToken);
        return Result.success(groupFeignService.checkIsGroupAdmin(questionId));
    }

    /** 内部调用凭证必须与服务配置一致；不一致统一按无权限拒绝。 */
    private void checkInternalToken(String requestToken) {
        if (!internalToken.equals(requestToken)) {
            throw new BizException(QuizNestErrorCode.INTERNAL_CALL_UNAUTHORIZED);
        }
    }
}
