package org.example.serviceapi.dto.ai;

import lombok.Data;

/**
 * 函数题题面重写请求（service-question -> service-ai 内部调用）。
 *
 * <p>AI 只依据调用方提供的<b>自有素材</b>（解法源码、参考实现/对拍代码、作者笔记）
 * 原创重述题意，不接收也不回传任何第三方题目原文，避免版权风险。</p>
 */
@Data
public class FunctionDescriptionRequest {
    /** 题目标题（调用方提供的名称，可被 AI 润色措辞，含义不变）。 */
    private String title;
    /** 提交类名，通常为 Solution。 */
    private String className;
    /** 函数名。 */
    private String methodName;
    /** 参数配置 JSON（[{type,name}]，顺序即参数顺序）。 */
    private String parameterConfig;
    /** 返回值类型。 */
    private String outputType;
    /** 期望难度（1 简单 / 2 中等 / 3 困难），可空，供 AI 参考。 */
    private Integer difficulty;
    /** 期望标签（逗号分隔），可空，供 AI 参考。 */
    private String tags;
    /** 解法源码：必须是通过本地对拍验证的实现，是题意推导的主要依据。 */
    private String solutionCode;
    /** 参考实现或对拍测试代码，可空，用于与解法交叉印证题意。 */
    private String referenceCode;
    /** 作者笔记/思路，可空，属于调用方自有内容。 */
    private String notes;
}
