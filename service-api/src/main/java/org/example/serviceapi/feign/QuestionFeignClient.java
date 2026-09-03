package org.example.serviceapi.feign;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.judge.JudgeContextDto;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.dto.question.QuestionDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "service-question")
public interface QuestionFeignClient {
    @GetMapping("/api/question/info/{questionId}")
    Result<QuestionDto> getQuestionInfo(@PathVariable Long questionId);
    @PostMapping("/api/question/info/favoritequestions")
    Result<List<QuestionDto>> getFavorites(@RequestBody List<Long> questionIds);

    /**
     * 按 ID 批量拉取题目瘦身信息（不含题干/样例等大字段）。
     *
     * <p>返回的 status/createUserId 供调用方做可见性过滤，适合收藏夹条目列表等场景。</p>
     */
    @PostMapping("/api/question/info/briefquestions")
    Result<List<QuestionBriefDto>> getFavoritesBrief(@RequestBody List<Long> questionIds);

    /**
     * 按判题记录拉取判题上下文（代码、日志、输入输出、题目描述等大字段）。
     *
     * <p>仅供服务间内部调用：请求经由统一 Feign 拦截器注入 X-Internal-Token，
     * 由 UserAuthInterceptor 校验；不存在对外公网暴露的鉴权面。</p>
     *
     * @param judgeRecordId 判题记录 ID
     * @return 判题上下文；记录不存在时返回 error
     */
    @GetMapping("/api/question/internal/judge-context/{judgeRecordId}")
    Result<JudgeContextDto> getJudgeContext(@PathVariable("judgeRecordId") Long judgeRecordId);
}
