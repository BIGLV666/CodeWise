package org.example.servicejudge.controller;

import org.example.serviceapi.dto.Result;
import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.servicejudge.judge.JudgeService;
import org.example.servicejudge.vo.DockersStatusVo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RateLimit(limit = 60, window = 60)
@RequestMapping("/api/judge/containers")
public class DockerController {

    private final JudgeService judgeService;

    public DockerController(JudgeService judgeService) {
        this.judgeService = judgeService;
    }

    @GetMapping
    public Result<List<DockersStatusVo>> getContainers() {
        return Result.success(judgeService.getDockers());
    }

    @PostMapping("/{language}")
    public Result<Void> addContainer(@PathVariable String language) {
        judgeService.addDocker(language);
        return Result.success("扩容成功");
    }

    @DeleteMapping("/{language}/{containerId}")
    public Result<Void> removeContainer(
            @PathVariable String language,
            @PathVariable String containerId
    ) {
        judgeService.removeDocker(language, containerId);
        return Result.success("删除成功");
    }
}
