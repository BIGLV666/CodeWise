package org.example.servicequestion.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * agent 批量题目详情的逐题结果。
 *
 * <p>批量查询不因个别题目失败而整体报错：每题给出独立 state，
 * 仅 state=ok 时携带完整题目信息。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentQuestionDetailVo {

    /** 查询成功，question 字段有效。 */
    public static final String STATE_OK = "ok";
    /** 题目不存在。 */
    public static final String STATE_NOT_FOUND = "not_found";
    /** 题目存在但当前用户不可见（下架/审核中/他人私密题）。 */
    public static final String STATE_INVISIBLE = "invisible";

    /** 题目 ID。 */
    private Long questionId;
    /** 结果状态：ok / not_found / invisible。 */
    private String state;
    /** 不可见等原因说明；state=ok 时为 null。 */
    private String message;
    /** 完整题目详情；仅 state=ok 时有效。 */
    private QuestionVo question;

    public static AgentQuestionDetailVo ok(Long questionId, QuestionVo question) {
        return new AgentQuestionDetailVo(questionId, STATE_OK, null, question);
    }

    public static AgentQuestionDetailVo notFound(Long questionId) {
        return new AgentQuestionDetailVo(questionId, STATE_NOT_FOUND, "题目不存在", null);
    }

    public static AgentQuestionDetailVo invisible(Long questionId, String message) {
        return new AgentQuestionDetailVo(questionId, STATE_INVISIBLE, message, null);
    }
}
