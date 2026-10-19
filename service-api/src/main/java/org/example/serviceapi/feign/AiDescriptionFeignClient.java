package org.example.serviceapi.feign;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.ai.FunctionDescriptionRequest;
import org.example.serviceapi.dto.ai.FunctionDescriptionResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 函数题题面重写（service-question -> service-ai）。
 *
 * <p>走 service-ai 的「指定端点」路径（默认 opencode-go）：该端点不参与 AUTO 轮转，
 * 需要会话标识，服务端用固定种子派生会话 id。</p>
 *
 * <p>{@code contextId} 必须显式给出：同一个应用里按 {@code name} 复用的 Feign 客户端
 * 会共用同一个 {@code <name>.FeignClientSpecification} Bean，第二个客户端（本类与
 * {@link AiArtifactFeignClient} 都指向 service-ai）注册时会直接让应用启动失败。</p>
 */
@FeignClient(name = "service-ai", contextId = "aiDescription")
public interface AiDescriptionFeignClient {

    @PostMapping("/api/ai/internal/function-description")
    Result<FunctionDescriptionResponse> generateFunctionDescription(
            @RequestHeader("X-CodeWise-Internal-Token") String internalToken,
            @RequestBody FunctionDescriptionRequest request
    );
}
