package org.example.servicecommunity.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicecommunity.Dto.SolutionDto;
import org.example.servicecommunity.service.SolutionService;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomeSolutionVo;
import org.example.servicecommunity.vo.SolutionVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/community/solutions")
public class SolutionController {
    @Autowired
    private SolutionService solutionService;

    /** 发布题解。用户 ID 从登录上下文获取，请求体无需传作者信息。需要一次性请求id */
    @PostMapping
    public Result<HomeSolutionVo> createSolution(@RequestBody SolutionDto dto,@RequestParam String requestId) {
        return Result.success(solutionService.createSolution(dto,requestId));
    }

    /** 按题目 ID 查询题解，首次不传 lastId，后续传上页返回的 nextCursor。 */
    @GetMapping
    public Result<CursorPageResult<HomeSolutionVo>> listSolutions(
            @RequestParam Long questionId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        validatePageSize(pageSize);
        return Result.success(solutionService.listSolutions(questionId, lastId, pageSize));
    }

    /** 查询题解详情，并累计浏览次数。 */
    @GetMapping("/{solutionId}")
    public Result<SolutionVo> getSolution(@PathVariable Long solutionId) {
        return Result.success(solutionService.getSolution(solutionId));
    }

    /** 修改自己的题解，solutionId 以路径参数为准。 */
    @PutMapping("/{solutionId}")
    public Result<HomeSolutionVo> updateSolution(@PathVariable Long solutionId,
                                                 @RequestBody SolutionDto dto) {
        return Result.success(solutionService.updateSolution(solutionId, dto));
    }

    /** 删除自己的题解，并级联清理题解标签、评论和评论点赞。 */
    @DeleteMapping("/{solutionId}")
    public Result<String> deleteSolution(@PathVariable Long solutionId) {
        solutionService.deleteSolution(solutionId);
        return Result.success("success");
    }

    private void validatePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize 必须在 1 到 100 之间");
        }
    }
}
