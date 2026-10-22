package org.example.servicequestion.dto;

import lombok.Data;
import org.example.serviceapi.dto.ai.FunctionParameterRange;

import java.util.List;

/**
 * 批量导入中的单条题目。
 *
 * <p>{@code solutionCode} 必须是<b>已通过本地对拍验证</b>的 Java 实现：它既作为题面的推导依据，
 * 也作为判题生成隐藏用例时的标准答案。{@code parameterConfig}/{@code outputType} 需与之一致，
 * 且类型限于 int/long/String/int[]/String[]/int[][]/ListNode/TreeNode。</p>
 */
@Data
public class FunctionBatchImportItem {
    /** 题目标题。 */
    private String title;
    /** 题面正文：为空且允许 AI 重写时由 service-ai 生成，否则直接使用本字段。 */
    private String description;
    /** 输入说明，可空。 */
    private String inputDesc;
    /** 输出说明，可空。 */
    private String outputDesc;
    /** 提示，可空。 */
    private String hint;
    /** 难度 1/2/3，可空（缺省 1；AI 重写题面时会给出建议值，仅在本字段为空时采用）。 */
    private Integer difficulty;
    /** 标签（逗号分隔），可空。 */
    private String tags;
    /** 来源标注，可空（缺省取请求级 defaultSource）。 */
    private String source;
    /** 时间限制（毫秒），可空，缺省 2000。 */
    private Integer timeLimit;
    /** 内存限制（MB），可空，缺省 256。 */
    private Integer memoryLimit;

    /** 提交类名，缺省 Solution。 */
    private String className;
    /** 函数名（必填）。 */
    private String methodName;
    /** 参数配置 JSON（必填）。 */
    private String parameterConfig;
    /** 返回值类型（必填）。 */
    private String outputType;
    /** 标准答案源码（必填，判题侧要求可编译出 class Solution）。 */
    private String solutionCode;
    /** 参考实现/对拍测试代码，可空；仅用于题面重写时交叉印证题意，不参与判题。 */
    private String referenceCode;
    /** 作者笔记/思路，可空；仅用于题面重写。 */
    private String notes;
    /**
     * 参数取值域（可空）：手工指定时优先于 AI 的建议，用于兜住模型给错约束的题。
     * 字段含义见 {@link org.example.serviceapi.dto.ai.FunctionParameterRange}。
     */
    private List<FunctionParameterRange> parameterRanges;
    /** 本题的隐藏用例数量，覆盖请求级 caseCount。 */
    private Integer caseCount;
    /** 本题随机种子，覆盖请求级 seed。 */
    private Long seed;
}
