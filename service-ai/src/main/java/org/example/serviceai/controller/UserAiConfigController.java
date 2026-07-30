package org.example.serviceai.controller;

import org.example.serviceai.dto.AiModelQueryDto;
import org.example.serviceai.dto.UserAiConfigDto;
import org.example.serviceai.service.UserAiService;
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

    private final UserAiService userAiService;

    public UserAiConfigController(UserAiService userAiService) {
        this.userAiService = userAiService;
    }

    @GetMapping
    public Result<List<HomeUserConfigVo>> getAllConfigs() {
        return Result.success(userAiService.getAllConfigs());
    }

    @GetMapping("/{configId}")
    public Result<UserAiConfigVo> getConfig(@PathVariable Long configId) {
        return Result.success(userAiService.getConfig(configId));
    }

    @PostMapping
    public Result<UserAiConfigVo> createConfig(@RequestBody UserAiConfigDto dto) {
        return Result.success(userAiService.createConfig(dto));
    }

    @PutMapping("/{configId}")
    public Result<UserAiConfigVo> updateConfig(
            @PathVariable Long configId,
            @RequestBody UserAiConfigDto dto
    ) {
        return Result.success(userAiService.updateConfig(configId, dto));
    }

    @DeleteMapping("/{configId}")
    public Result<Void> deleteConfig(@PathVariable Long configId) {
        userAiService.deleteConfig(configId);
        return Result.success("删除成功");
    }

    @PostMapping("/models")
    public Result<List<String>> fetchModels(@RequestBody AiModelQueryDto dto) {
        return Result.success(userAiService.fetchModels(dto.getBaseUrl(), dto.getApiKey()));
    }
}
