package org.example.serviceai.controller;

import io.github.biglv666.apigovernance.annotation.Skip;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.service.FunctionDescriptionService;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.ai.FunctionDescriptionRequest;
import org.example.serviceapi.dto.ai.FunctionDescriptionResponse;
import org.example.serviceapi.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 函数题题面重写内部接口（service-question 导入题目时经 Feign 调用）。
 *
 * <p>不参与限流也不面向浏览器：鉴权只用内部 Token，暴露在网关外会拿到 401 文案。</p>
 */
@RestController
@Skip(reason = "系统内部端点（service-question 导入函数题时重写题面经 Feign 调用），鉴权依赖 X-CodeWise-Internal-Token 校验，不参与限流")
@RequestMapping("/api/ai/internal")
public class FunctionDescriptionController {
    private final FunctionDescriptionService functionDescriptionService;
    private final String internalToken;

    public FunctionDescriptionController(
            FunctionDescriptionService functionDescriptionService,
            @Value("${codewise.internal-token}") String internalToken
    ) {
        this.functionDescriptionService = functionDescriptionService;
        this.internalToken = internalToken;
    }

    @PostMapping("/function-description")
    public Result<FunctionDescriptionResponse> generate(
            @RequestHeader("X-CodeWise-Internal-Token") String requestToken,
            @RequestBody FunctionDescriptionRequest request
    ) {
        if (!internalToken.equals(requestToken)) {
            throw new BizException(AiErrorCode.INTERNAL_CALL_UNAUTHORIZED);
        }
        return Result.success(functionDescriptionService.generate(request));
    }
}
