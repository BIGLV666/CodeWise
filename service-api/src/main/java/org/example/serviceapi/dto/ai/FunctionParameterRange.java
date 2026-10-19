package org.example.serviceapi.dto.ai;

import lombok.Data;

/**
 * 单个参数的取值域建议（service-ai 在重写题面时顺带给出）。
 *
 * <p>目的是让「题面里写出来的约束」与「平台实际生成的测试数据」一致：题面写 depth 是正整数，
 * 数据里就不该出现 -852；题面写链表节点是 0~9 的数字，数据里就不该出现 -40。
 * 字段含义与随机输入生成器一一对应，未给出的项由平台默认值兜底，非法值由调用方裁剪，
 * 且只有能对上参数名的建议才会生效（按 {@code name} 匹配）。</p>
 */
@Data
public class FunctionParameterRange {
    /** 参数名，必须与函数签名里的参数名一致。 */
    private String name;
    /** 数值下限：int/long 的取值、数组与链表/树的元素值。 */
    private Long min;
    /** 数值上限。 */
    private Long max;
    /** 长度/个数/行数/节点数下限：String 长度、数组元素数、矩阵行数、链表节点数、树节点数。 */
    private Integer minSize;
    /** 长度/个数/行数/节点数上限。 */
    private Integer maxSize;
    /** 二维数组的内层长度（列数）下限。 */
    private Integer innerMinSize;
    /** 二维数组的内层长度（列数）上限。 */
    private Integer innerMaxSize;
    /** 字符串数组里单个字符串的长度下限。 */
    private Integer elementMinSize;
    /** 字符串数组里单个字符串的长度上限。 */
    private Integer elementMaxSize;
}
