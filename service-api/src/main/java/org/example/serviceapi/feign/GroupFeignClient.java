package org.example.serviceapi.feign;

import org.example.serviceapi.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * service-quiz-nest 内部接口契约（小组权限查询）。
 *
 * <p>供 service-question 等服务判断某道小组题目是否对当前用户可见/可维护。
 * 仅限服务间调用：{@code X-CodeWise-Internal-Token} 由调用方按
 * {@code codewise.internal-token} 配置显式传入，网关不会注入该头，
 * 外部请求无法伪造。</p>
 */
@FeignClient(name = "service-quiz-nest")
public interface GroupFeignClient {

    /**
     * 判断当前用户是否可以读取指定私密题目。
     *
     * <p>公开题由调用方直接放行，不会走此接口；私密题无论是否为平台题，都要求
     * 用户是该组 APPROVED 成员，平台管理员（roleId=0/2）也可读。平台题一旦
     * 被小组引用，不能因为 {@code is_plat_from_question=1} 就绕过成员校验。</p>
     *
     * @param internalToken 内部调用凭证
     * @param questionId    题目 id
     * @return true 表示有权限；无权限时返回 code 非 200 的 {@link Result}
     */
    @GetMapping("/api/quiz/internal/questions/{questionId}/member")
    Result<Boolean> checkIsGroupMember(
            @RequestHeader("X-CodeWise-Internal-Token") String internalToken,
            @PathVariable("questionId") Long questionId);

    /**
     * 判断当前用户是否可以维护指定题目的样例。
     *
     * <p>平台题目不允许在小组内修改，直接返回 false；小组自建题目要求用户是该组
     * APPROVED 的 ROOT/ADMIN。</p>
     *
     * @param internalToken 内部调用凭证
     * @param questionId    题目 id
     * @return true 表示有权限；无权限时返回 code 非 200 的 {@link Result}
     */
    @GetMapping("/api/quiz/internal/questions/{questionId}/admin")
    Result<Boolean> checkIsGroupAdmin(
            @RequestHeader("X-CodeWise-Internal-Token") String internalToken,
            @PathVariable("questionId") Long questionId);
}
