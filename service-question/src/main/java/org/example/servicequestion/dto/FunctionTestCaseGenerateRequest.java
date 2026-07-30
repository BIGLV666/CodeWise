package org.example.servicequestion.dto;

import lombok.Data;

@Data
public class FunctionTestCaseGenerateRequest {
    private Long questionId;
    private String language;
    private String standardAnswer;
    private Integer count;
    private Long seed;
}
