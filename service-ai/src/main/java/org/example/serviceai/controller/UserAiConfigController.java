package org.example.serviceai.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceai.ai.config.UserAiConfigService;
import org.example.serviceai.dto.AiModelQueryDto;
import org.example.serviceai.dto.UserAiConfigDto;
import org.example.serviceai.vo.HomeUserConfigVo;
import org.example.serviceai.vo.UserAiConfigVo;
import org.example.serviceapi.dto.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/configs")
public class UserAiConfigController {

    private final UserAiConfigService userAiConfigService;

    public UserAiConfigController(UserAiConfigService userAiConfigService) {
        this.userAiConfigService = userAiConfigService;
    }

    @GetMapping
    @RateLimit(limit = 100, window = 60)
    public Result<List<HomeUserConfigVo>> getAllConfigs() {
        return Result.success(userAiConfigService.getAllConfigs());
    }

    @GetMapping("/{configId}")
    @RateLimit(limit = 100, window = 60)
    public Result<UserAiConfigVo> getConfig(@PathVariable Long configId) {
        return Result.success(userAiConfigService.getConfig(configId));
    }

    @PostMapping
    @RateLimit(limit = 20, window = 60)
    public Result<UserAiConfigVo> createConfig(@RequestBody UserAiConfigDto dto) {
        return Result.success(userAiConfigService.createConfig(dto));
    }

    @PutMapping("/{configId}")
    @RateLimit(limit = 30, window = 60)
    public Result<UserAiConfigVo> updateConfig(
            @PathVariable Long configId,
            @RequestBody UserAiConfigDto dto
    ) {
        return Result.success(userAiConfigService.updateConfig(configId, dto));
    }

    @DeleteMapping("/{configId}")
    @RateLimit(limit = 30, window = 60)
    public Result<Void> deleteConfig(@PathVariable Long configId) {
        userAiConfigService.deleteConfig(configId);
        return Result.success("删除成功");
    }

    @PostMapping("/models")
    @RateLimit(limit = 30, window = 60)
    public Result<List<String>> fetchModels(@RequestBody AiModelQueryDto dto) {
        return Result.success(userAiConfigService.fetchModels(dto.getBaseUrl(), dto.getApiKey()));
    }
}
