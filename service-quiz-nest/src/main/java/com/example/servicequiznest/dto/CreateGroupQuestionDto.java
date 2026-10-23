package com.example.servicequiznest.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateGroupQuestionDto {

    //所属小组id
    Long groupId;
    /**平台题目id。该字段如果不为空认为添加的题目为平台题目，届时将不再使用其他字段*/
    private Long questionId;
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

}
