package org.example.serviceapi.feign;

import org.example.serviceapi.dto.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "service-question")
public interface QuestionFeignClient {
    @GetMapping("/api/question/info/{questionId}")
    Result<QuestionDto> getQuestionInfo(@PathVariable Long questionId);
}
