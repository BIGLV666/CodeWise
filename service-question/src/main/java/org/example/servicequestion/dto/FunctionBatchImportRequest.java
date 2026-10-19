package org.example.servicequestion.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量导入函数题请求（管理员）：一批题目一次性提交，服务端异步逐条处理。
 *
 * <p>用于把平台外已有的、已验证过解的题目成批搬进题库：每条自带函数签名与标准答案，
 * 题面缺省时由 service-ai 依据解法源码原创重写。</p>
 */
@Data
public class FunctionBatchImportRequest {
    /** 题目列表，1 ~ 50 条。 */
    private List<FunctionBatchImportItem> items;
    /** 每题的隐藏用例数量（1 ~ 100），缺省 20。 */
    private Integer caseCount;
    /** 缺省随机种子：同一批题共用的基础种子，缺省按题目序号派生，便于复现。 */
    private Long seed;
    /** 是否允许在缺少题面时调用 AI 重写（缺省 true）；为 false 时缺题面的条目直接判失败。 */
    private Boolean allowDescriptionGeneration;
    /** 来源标注（写入 question.source），缺省「原创改编」。 */
    private String defaultSource;
}
