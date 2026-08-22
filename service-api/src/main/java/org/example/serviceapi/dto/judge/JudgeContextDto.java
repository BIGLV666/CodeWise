package org.example.serviceapi.dto.judge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 判题上下文 DTO。
 *
 * <p>AI 建议消息瘦身后只携带 ID 引用；service-ai 通过 question 服务的
 * 内部端点按 judgeRecordId 拉取本 DTO，获取代码、日志、输入输出等
 * 大字段。仅在服务间内部调用（X-Internal-Token 校验）使用，
 * 不对公网暴露。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeContextDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 判题记录 ID */
    private Long judgeRecordId;
    /** 提交记录 ID */
    private Long submitRecordId;
    /** 题目 ID */
    private Long questionId;
    /** 提交语言 */
    private String language;
    /** 判题结果状态：AC/WA/RE/TLE 等 */
    private String judgeStatus;
    /** 用户提交代码（大字段，按需拉取） */
    private String code;
    /** 运行日志（大字段，按需拉取） */
    private String log;
    /** 失败用例输入（大字段，按需拉取） */
    private String inputData;
    /** 失败用例期望输出（大字段，按需拉取） */
    private String expectedOutput;
    /** 用户实际输出（大字段，按需拉取） */
    private String userOutput;
    /** 题目描述（大字段，按需拉取） */
    private String questionContent;
}
