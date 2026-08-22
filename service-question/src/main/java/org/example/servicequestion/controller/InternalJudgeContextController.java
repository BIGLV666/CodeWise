package org.example.servicequestion.controller;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.judge.JudgeContextDto;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.JudgeRecord;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.entry.SubmitRecord;
import org.example.servicequestion.mapper.JudgeRecordMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 内部判题上下文端点（仅供服务间 Feign 调用）。
 *
 * <p>AI 建议消息瘦身后只携带 judgeRecordId，service-ai 通过
 * {@code QuestionFeignClient#getJudgeContext} 回拉代码、日志、输入输出、
 * 题目描述等大字段，组装成本端点返回的 {@link JudgeContextDto}。</p>
 *
 * <p><b>鉴权边界（防 IDOR，本端点数据含用户代码）：</b></p>
 * <ul>
 *   <li>{@code UserContext.getUserId() == null}：视为系统内部调用放行——
 *       MQ 消费线程发起的 Feign 调用经统一拦截器只透传 X-Internal-Token，
 *       不携带任何用户头；请求能到达本端点即说明内部 Token 已通过
 *       UserAuthInterceptor 校验；</li>
 *   <li>{@code userId != null}：请求经网关转发且携带用户身份，此时必须是
 *       管理员（roleId==2）才允许查看他人代码，普通用户访问直接拒绝。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/question/internal")
public class InternalJudgeContextController {

    /** 管理员角色 ID（与 service-user 角色表约定一致） */
    private static final int ADMIN_ROLE_ID = 2;

    @Autowired
    private JudgeRecordMapper judgeRecordMapper;

    @Autowired
    private SubmitRecordMapper submitRecordMapper;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private UserFeignClient userFeignClient;

    /**
     * 按判题记录拉取判题上下文（代码、日志、输入输出、题目描述等大字段）。
     *
     * <p>鉴权规则见类注释：无用户上下文视为系统内部调用放行；
     * 携带用户上下文时必须是管理员（roleId==2），非管理员抛出
     * {@link IllegalArgumentException}，由全局异常处理器转为错误 Result。</p>
     *
     * @param judgeRecordId 判题记录 ID
     * @return 判题上下文；任一关联记录缺失时返回 {@code Result.error}（不抛堆栈）
     */
    @GetMapping("/judge-context/{judgeRecordId}")
    public Result<JudgeContextDto> getJudgeContext(@PathVariable("judgeRecordId") Long judgeRecordId) {
        checkInternalOrAdmin();

        if (judgeRecordId == null) {
            return Result.error("判题记录 ID 不能为空");
        }

        JudgeRecord judgeRecord = judgeRecordMapper.selectById(judgeRecordId);
        if (judgeRecord == null) {
            return Result.error("判题记录不存在: " + judgeRecordId);
        }

        SubmitRecord submitRecord = submitRecordMapper.selectById(judgeRecord.getSubmitRecordId());
        if (submitRecord == null) {
            return Result.error("提交记录不存在: " + judgeRecord.getSubmitRecordId());
        }

        Question question = questionMapper.selectById(submitRecord.getQuestionId());
        if (question == null) {
            return Result.error("题目不存在: " + submitRecord.getQuestionId());
        }

        JudgeContextDto context = JudgeContextDto.builder()
                .judgeRecordId(judgeRecord.getJudgeRecordId())
                .submitRecordId(judgeRecord.getSubmitRecordId())
                .questionId(submitRecord.getQuestionId())
                .language(submitRecord.getLanguage())
                .judgeStatus(judgeRecord.getSubmitStatus())
                .code(judgeRecord.getCode())
                .log(judgeRecord.getLog())
                .inputData(judgeRecord.getInputData())
                .expectedOutput(judgeRecord.getExpectedOutput())
                .userOutput(judgeRecord.getUserOutput())
                .questionContent(question.getDescription())
                .build();
        return Result.success(context);
    }

    /**
     * 鉴权边界校验：无用户上下文（系统内部 Feign 调用）放行；
     * 有用户上下文时必须是管理员（roleId==2），否则拒绝。
     */
    private void checkInternalOrAdmin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            // MQ 消费线程经 Feign 透传不携带用户头，视为系统内部调用，放行
            return;
        }
        Result<UserDto> response = userFeignClient.getUserInfo(userId);
        if (response == null || response.getData() == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (!Objects.equals(response.getData().getRoleId(), ADMIN_ROLE_ID)) {
            throw new IllegalArgumentException("无权查看判题上下文");
        }
    }
}
