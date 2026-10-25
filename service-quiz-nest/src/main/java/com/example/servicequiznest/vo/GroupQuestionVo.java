package com.example.servicequiznest.vo;

import org.example.serviceapi.dto.question.QuestionDto;

public record GroupQuestionVo(QuestionDto questionDto, Long groupQuestionId) {
}
