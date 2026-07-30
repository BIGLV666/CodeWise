package org.example.serviceapi.feign;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateRequest;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "service-ai")
public interface AiArtifactFeignClient {
    @PostMapping("/api/ai/internal/function-artifacts")
    Result<FunctionArtifactGenerateResponse> generateFunctionArtifacts(
            @RequestHeader("X-CodeWise-Internal-Token") String internalToken,
            @RequestBody FunctionArtifactGenerateRequest request
    );
}
