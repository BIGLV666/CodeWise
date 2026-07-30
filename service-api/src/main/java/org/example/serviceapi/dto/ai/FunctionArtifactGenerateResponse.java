package org.example.serviceapi.dto.ai;

import lombok.Data;

@Data
public class FunctionArtifactGenerateResponse {
    /** AI 只返回随机数据生成器源码，不返回任何测试样例。 */
    private String generatorCode;
    private String standardAnswerCode;
    private String contractVersion;
}
