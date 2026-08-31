package org.example.servicecommunity.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicecommunity.enums.PostType;
import org.example.servicecommunity.service.MyContentService;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.MyContentVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户查看自己发布内容的接口。身份只从 UserContext 获取。 */
@RestController
@RequestMapping("/api/community/mine")
public class MyContentController {
    private final MyContentService myContentService;

    public MyContentController(MyContentService myContentService) {
        this.myContentService = myContentService;
    }

    @GetMapping
    @RateLimit(limit = 100, window = 60)
    public Result<CursorPageResult<MyContentVo>> list(
            @RequestParam PostType type,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.success(myContentService.list(type, lastId, pageSize));
    }
}
