package com.example.servicequiznest.vo;

import org.example.serviceapi.dto.question.QuestionBriefDto;

public record MinimalQuestionVo(QuestionBriefDto dto,Long groupQuestionId){}
