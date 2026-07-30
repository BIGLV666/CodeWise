package org.example.serviceai.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiTaskRootDto {
    private Long userId;
    private Long questionId;

    /**
     * 当前已经压缩完成的完整代码快照。
     * AI 每次只读取该快照和快照之后产生的 changes，判断结束后再合并成新快照。
     */
    private String code;

    /**
     * 用户打开题目并创建观察会话的时间。
     * 该时间在整个题目编辑会话中保持不变，用于计算首次 20 分钟保护期。
     */
    private LocalDateTime createTime;

    /** 用户最近一次产生代码修改的时间，用于避免在用户正在输入时打断。 */
    private LocalDateTime lastChangeTime;

    /** 最近一次完成 AI 静默判断的时间，包括 AI 返回 OK 的情况。 */
    private LocalDateTime lastEvaluatedTime;

    /** 最近一次收到前端编辑或心跳请求的时间，用于排除用户已经离开页面。 */
    private LocalDateTime lastActiveTime;
}
