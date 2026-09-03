package org.example.servicequestion.dto;

import lombok.Data;

import java.util.List;

/**
 * agent 批量题目详情请求体。
 */
@Data
public class AgentQuestionDetailDto {
    /** 题目 ID 列表，必填，去重后 1-10 个。 */
    private List<Long> questionIds;
}
