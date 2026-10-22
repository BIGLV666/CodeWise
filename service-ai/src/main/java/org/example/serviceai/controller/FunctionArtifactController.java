package org.example.serviceai.controller;

import io.github.biglv666.apigovernance.annotation.Skip;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceai.service.FunctionArtifactService;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateRequest;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateResponse;
import org.example.serviceapi.exception.BizException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

@RestController
@Skip(reason = "系统内部端点（service-question 生成函数题产物经 Feign 调用），鉴权依赖 X-CodeWise-Internal-Token 校验，不参与限流")
@RequestMapping("/api/ai/internal")
public class FunctionArtifactController {
    private final FunctionArtifactService functionArtifactService;
    private final String internalToken;

    public FunctionArtifactController(
            FunctionArtifactService functionArtifactService,
            @Value("${codewise.internal-token}") String internalToken
    ) {
        this.functionArtifactService = functionArtifactService;
        this.internalToken = internalToken;
    }

    @PostMapping("/function-artifacts")
    public Result<FunctionArtifactGenerateResponse> generate(
            @RequestHeader("X-CodeWise-Internal-Token") String requestToken,
            @RequestBody FunctionArtifactGenerateRequest request
    ) {
        if (!internalToken.equals(requestToken)) {
            throw new BizException(AiErrorCode.INTERNAL_CALL_UNAUTHORIZED);
        }
        return Result.success(functionArtifactService.generate(request));
    }
}
