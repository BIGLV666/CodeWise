package org.example.servicequestion.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicequestion.dto.AgentQuestionDetailDto;
import org.example.servicequestion.service.QuestionService;
import org.example.servicequestion.vo.AgentQuestionDetailVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * codewise-agent 专用题目接口（与网页端 {@link QuestionController} 分离成类）。
 *
 * <p>提供按 ID 批量拉取完整题目的能力，供 agent 在收藏夹条目列表（瘦身）
 * 之上按需取详情；可见性判定与网页端单题查询一致，逐题独立报告结果。</p>
 *
 * <p>身份安全：userId 取自网关注入的 {@code UserContext}，不接受客户端提交的用户标识。</p>
 */
@RestController
@RequestMapping("/api/question")
public class AgentQuestionController {

    @Autowired
    private QuestionService questionService;

    /**
     * 批量查询题目详情：逐题做可见性判定（私密题仅创建者与管理员可见），
     * 不存在的题目、不可见的题目以独立状态返回，不整体失败。
     */
    @PostMapping("/agent/detail")
    @RateLimit(limit = 100, window = 60)
    public Result<List<AgentQuestionDetailVo>> getQuestionDetails(@RequestBody AgentQuestionDetailDto dto) {
        return Result.success(questionService.getQuestionDetailsBatch(dto.getQuestionIds()));
    }
}
