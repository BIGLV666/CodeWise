package org.example.servicequestion.Feign;

import io.github.biglv666.apigovernance.annotation.Skip;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.exception.BizException;
import org.example.servicequestion.FeugnService.QuestionServiceFeign;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/question")
@Slf4j
public class QuestionFegin {

    private final QuestionServiceFeign questionServiceFeign;
    private final String internalToken;


    public QuestionFegin(
            QuestionServiceFeign questionServiceFeign,
            @Value("${codewise.internal-token}") String internalToken) {
        this.questionServiceFeign = questionServiceFeign;
        this.internalToken = internalToken;
    }

    @GetMapping("/info/{questionId}")
    public Result<QuestionDto> getQuestion(@PathVariable Long questionId) {
        return Result.success(questionServiceFeign.getQuestionById(questionId));
    }
    @PostMapping("/info/favoritequestions")
    public Result<List<QuestionDto>> getFavorites(@RequestBody List<Long> questionIds) {
        return Result.success(questionServiceFeign.getAllFavoritesByUserId(questionIds));
    }
    /**
     * 按 ID 批量返回题目瘦身信息（不含题干/样例等大字段），供收藏夹条目列表等服务间查询。
     */
    @PostMapping("/info/briefquestions")
    public Result<List<QuestionBriefDto>> getFavoritesBrief(@RequestBody List<Long> questionIds) {
        return Result.success(questionServiceFeign.getAllFavoritesBrief(questionIds));
    }

    /**
     * 供 quiz-nest 同步创建小组自建题目。
     *
     * <p>鉴权依赖 {@code X-CodeWise-Internal-Token}：网关只注入 {@code X-Internal-Token}，
     * 不会注入该头，外部请求无法伪造；内部调用不占用户维度限流。</p>
     */
    @PostMapping("/internal/add")
    @Skip(reason = "系统内部端点（quiz-nest 添加小组自建题目经 Feign 调用），鉴权依赖 X-CodeWise-Internal-Token 校验，不参与限流")
    public Result<QuestionDto> addQuestion(
            @RequestHeader("X-CodeWise-Internal-Token") String requestToken,
            @RequestBody QuestionDto questionDto) {
        if (!internalToken.equals(requestToken)) {
            throw new BizException(QuestionErrorCode.INTERNAL_CALL_UNAUTHORIZED);
        }
        return Result.success(questionServiceFeign.addQuestion(questionDto));
    }


    @DeleteMapping("/internal/delete")
    @Skip(reason = "系统内部端点（quiz-nest 删除小组自建题目经 Feign 调用），鉴权依赖 X-CodeWise-Internal-Token 校验，不参与限流")
    Result<String> deleteQuestion(
            @RequestHeader("X-CodeWise-Internal-Token") String requestToken,
            @RequestParam Long questionId){
        if (!internalToken.equals(requestToken)) {
            throw new BizException(QuestionErrorCode.INTERNAL_CALL_UNAUTHORIZED);
        }
        try{
            questionServiceFeign.deleteQuestion(questionId);
        }catch (Exception e){
            log.error("小组自建题目删除失败，id为{}",questionId,e);
            throw new BizException(QuestionErrorCode.DELETE_GROUP_QUESTION_FAILED, e);
        }
        return Result.success("success");
    }
}
