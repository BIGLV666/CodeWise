package org.example.serviceai.controller;

import org.example.serviceai.service.FunctionArtifactService;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateRequest;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;

@RestController
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
            throw new SecurityException("内部服务认证失败");
        }
        return Result.success(functionArtifactService.generate(request));
    }
}
