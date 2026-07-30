package org.example.serviceai.vo;

import lombok.Data;

@Data
public class AiTaskVo {
    private String advice;
    private Long questionId;
    private String type;
}
