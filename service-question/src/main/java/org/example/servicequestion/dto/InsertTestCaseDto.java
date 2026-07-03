package org.example.servicequestion.dto;

import lombok.Data;

@Data
public class InsertTestCaseDto {

    private String inputData;

    private String expectedOutput;

    private Integer isSample;

    private Integer isHidden;

    private Integer sortOrder;

    private Integer scoreWeight;

    private Integer timeLimit;

    private Integer memoryLimit;
}
