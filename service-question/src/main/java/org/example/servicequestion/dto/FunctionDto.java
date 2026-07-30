package org.example.servicequestion.dto;

import lombok.Data;
import org.example.servicequestion.vo.FunctionSampleVo;

import java.util.List;

@Data
public class FunctionDto {
    private Long createUserId;
    private String title;
    private String description;
    private String inputDesc;
    private String outputDesc;


    private String hint;//提示
    private String source;//题目来源
    private Integer difficulty;//1-简单 2-
    private String tags;//标签
    private Integer timeLimit;
    private Integer memoryLimit;

    private String className;
    private String methodName;
    private String parameterConfig;
    private String outputType;
    private List<FunctionSampleVo> samples;

}
