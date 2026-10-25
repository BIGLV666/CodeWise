package com.example.servicequiznest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class UpdateGroupQuestionDto {

    //修改用dto，该字段必填
    private Long questionId;
    //所属小组id
    private String title;
    private String description;
    private String inputDesc;
    private String outputDesc;
    private String sampleInput;
    private String sampleOutput;
    private String hint;//提示

    private String tags;//标签

    private Integer timeLimit;
    private Integer memoryLimit;
    public UpdateGroupQuestionDto(){}
}
