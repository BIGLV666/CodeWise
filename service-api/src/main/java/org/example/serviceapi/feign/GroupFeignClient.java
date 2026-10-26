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

    /**
     * 小组提交判题：判断题目是否属于该小组，且当前用户是否为该小组 APPROVED 成员。
     *
     * <p>小组提交与普通提交不同：即使是公开题，也必须同时满足「题目在组内」和
     * 「用户是组员」，不能因为题目公开就绕过小组归属校验。</p>
     *
     * @param internalToken 内部调用凭证
     * @param questionId    题目 id
     * @param groupId       小组 id
     * @return true 表示有权限；无权限时返回 code 非 200 的 {@link Result}
     */
    @GetMapping("/api/quiz/internal/questions/{questionId}/{groupId}/member")
    Result<Boolean> checkQuestionInGroupAndIsGroupMember(
            @RequestHeader("X-CodeWise-Internal-Token") String internalToken,
            @PathVariable("questionId") Long questionId,
            @PathVariable("groupId") Long groupId);

    /**
     * 判断当前用户是否为指定小组的 APPROVED 成员。
     *
     * <p>用于不依赖题目的小组级只读接口（如小组提交记录列表）鉴权。</p>
     *
     * @param internalToken 内部调用凭证
     * @param groupId       小组 id
     * @return true 表示有权限；无权限时返回 code 非 200 的 {@link Result}
     */
    @GetMapping("/api/quiz/internal/groups/{groupId}/member")
    Result<Boolean> checkIsGroupMemberByGroupId(
            @RequestHeader("X-CodeWise-Internal-Token") String internalToken,
            @PathVariable("groupId") Long groupId);
}
