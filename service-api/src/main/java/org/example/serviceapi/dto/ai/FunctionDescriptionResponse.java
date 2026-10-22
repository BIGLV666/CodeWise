package org.example.serviceapi.dto.ai;

import lombok.Data;

import java.util.List;

/**
 * 函数题题面重写结果。
 *
 * <p>返回的题面是模型基于调用方素材的原创表述：调用方应把它当作可编辑稿，
 * 落库前建议抽检（题意是否与解法一致、约束是否与平台生成的测试数据规模一致）。</p>
 */
@Data
public class FunctionDescriptionResponse {
    /** 题目描述（必填）。 */
    private String description;
    /** 输入说明。 */
    private String inputDesc;
    /** 输出说明。 */
    private String outputDesc;
    /** 提示。 */
    private String hint;
    /** 难度建议（1/2/3），解析失败时为 null，由调用方兜底。 */
    private Integer difficulty;
    /** 标签建议（逗号分隔）。 */
    private String tags;
    /**
     * 各参数的取值域建议：题面里写了什么约束，就给出对应的取值范围，
     * 让平台生成的测试数据与题面一致。可为空（表示该参数没有额外约束）。
     */
    private List<FunctionParameterRange> parameterRanges;
}
