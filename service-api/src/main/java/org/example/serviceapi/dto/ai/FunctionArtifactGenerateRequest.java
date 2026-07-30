package org.example.serviceapi.dto.ai;

import lombok.Data;

@Data
public class FunctionArtifactGenerateRequest {
    private String title;
    private String description;
    private String inputDesc;
    private String outputDesc;
    private String sampleInput;
    private String sampleOutput;
    private String hint;
    private String parameterConfig;
    private String outputType;
    private String className;
    private String methodName;
    private Integer timeLimit;
    private Integer memoryLimit;
}
