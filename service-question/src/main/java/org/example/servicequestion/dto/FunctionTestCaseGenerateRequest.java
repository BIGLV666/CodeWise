package org.example.servicequestion.dto;

import lombok.Data;

@Data
public class FunctionTestCaseGenerateRequest {
    private Long questionId;
    private String language;
    private String standardAnswer;
    private Integer count;
    private Long seed;
    /**
     * 只用于随机输入生成的参数配置覆盖（可空）：在落库配置（类型+名称）基础上追加
     * 取值域键（min/max/minSize/maxSize/...），让生成的用例满足题目约束。
     * 类型与顺序必须与落库配置一致，否则判题侧的 Main 会读错参数。
     */
    private String parameterConfigOverride;
}
