package org.example.serviceai.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceai.ai.AiFacade;
import org.example.serviceapi.dto.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台可选模型目录：前端据此把模型选择渲染成
 *
 * <pre>
 * AUTO                      ← 前端固定项，后端不下发（否则会被当成模型名回传回来）
 * 平台
 *   deepseek-v4-flash       ← 本接口返回
 *   glm-5.3
 * 用户分组                   ← GET /api/ai/configs 返回（只显示 groupName）
 * </pre>
 *
 * <p>只下发模型名、不下发服务商：用户看到的是"用哪个模型"，不是"用哪家的机器"。</p>
 *
 * <p>与 {@code POST /api/ai/configs/models} 不是一回事——那个是拿用户填的地址去探
 * 那个地址有哪些模型，这个是平台自己能提供什么。路径与语义都不重叠，别混用。</p>
 */
@RestController
@RequestMapping("/api/ai/models")
public class AiModelCatalogController {

    private final AiFacade aiFacade;

    public AiModelCatalogController(AiFacade aiFacade) {
        this.aiFacade = aiFacade;
    }

    @GetMapping
    @RateLimit(limit = 100, window = 60)
    public Result<List<String>> platformModels() {
        return Result.success(aiFacade.platformModels());
    }
}
