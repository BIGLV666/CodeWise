package org.example.servicequestion.FeugnService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.exception.BizException;
import org.example.servicequestion.dto.InsertQuestionDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.TestCaseMapper;
import org.example.servicequestion.service.QuestionService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class QuestionServiceFeign {

    public QuestionServiceFeign(
            QuestionMapper questionMapper,
            QuestionService questionService,
            TestCaseMapper testCaseMapper
    ) {
        this.questionMapper = questionMapper;
        this.questionService = questionService;
        this.testCaseMapper=testCaseMapper;
    }
    private final QuestionMapper questionMapper;
    private final TestCaseMapper testCaseMapper;
    private final QuestionService questionService;

    public QuestionDto getQuestionById( Long id) {
        Question question = questionMapper.selectById(id);

        return ToQuestionDto(question);
    }
    public List<QuestionDto> getAllFavoritesByUserId(List<Long> questionIds) {
        List<QuestionDto> questionDtos = new ArrayList<>();

        if (questionIds == null || questionIds.isEmpty()) {
            return questionDtos;
        }

        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .in(Question::getQuestionId, questionIds));

        for (Question question : questions) {
            questionDtos.add(ToQuestionDto(question));
        }

        return questionDtos;
    }

    /**
     * 按 ID 批量返回题目瘦身信息（不含题干/样例/提示等大字段）。
     *
     * <p>供收藏夹条目列表等服务间批量查询：返回的 status/createUserId
     * 供调用方做可见性过滤（懒删除）。不存在的题目不会出现在结果中。</p>
     *
     * @param questionIds 题目 ID 列表
     * @return 瘦身题目列表（保持查询命中顺序）
     */
    public List<QuestionBriefDto> getAllFavoritesBrief(List<Long> questionIds) {
        List<QuestionBriefDto> briefs = new ArrayList<>();
        if (questionIds == null || questionIds.isEmpty()) {
            return briefs;
        }
        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .in(Question::getQuestionId, questionIds));
        for (Question question : questions) {
            briefs.add(ToQuestionBriefDto(question));
        }
        return briefs;
    }

    private QuestionBriefDto ToQuestionBriefDto(Question question) {
        return QuestionBriefDto.builder().questionId(question.getQuestionId()).title(question.getTitle())
                .difficulty(question.getDifficulty()).tags(question.getTags())
                .status(question.getStatus()).createUserId(question.getCreateUserId())
                .totalSubmit(question.getTotalSubmit()).totalAc(question.getTotalAc()).passRate(question.getPassRate())
                .createTime(question.getCreateTime()).build();
    }

    private QuestionDto ToQuestionDto(Question question) {
        return QuestionDto.builder().questionId(question.getQuestionId()).title(question.getTitle()).
               description(question.getDescription()).inputDesc(question.getInputDesc()).outputDesc(question.getOutputDesc())
               .sampleInput(question.getSampleInput()).sampleOutput(question.getSampleOutput()).hint(question.getHint()).source(question.getSource()).difficulty(question.getDifficulty()).tags(question.getTags()).
               timeLimit(question.getTimeLimit()).memoryLimit(question.getMemoryLimit()).status(question.getStatus()).aiStatue(question.getAiStatue()).createUserId(question.getCreateUserId()).createTime(question.getCreateTime())
               .updateTime(question.getUpdateTime()).totalSubmit(question.getTotalSubmit()).totalAc(question.getTotalAc()).passRate(question.getPassRate()).contentHash(question.getContentHash()).build();
    }

    /**
     * 供其他服务同步建题：复用 {@link QuestionService#addQuestion} 的入参校验、
     * 防重复提交锁与私密状态（status=3），不在 Feign 入口复制一套规则。
     *
     * @param questionDto 建题入参，questionId/status/createUserId 由本服务生成或控制
     * @return 已落库的题目（含自增 questionId）
     */
    public QuestionDto addQuestion(QuestionDto questionDto) {
        if (questionDto == null) {
            throw new BizException(QuestionErrorCode.QUESTION_SAVE_FAILED);
        }
        InsertQuestionDto insert = new InsertQuestionDto();
        BeanUtils.copyProperties(questionDto, insert);
        Question question = questionService.addQuestion(insert);
        return ToQuestionDto(question);
    }


    @Transactional
    public void deleteQuestion(Long id) {
        if(id == null)throw new BizException(QuestionErrorCode.QUESTION_ID_REQUIRED);
        //小组题目均为私密，所以在此只能删除私密的题目
        int r=questionMapper.deleteById(
                new QueryWrapper<Question>().eq("question_id", id).eq("status",3)
        );
        //r==0时候我们需要回源判断一次是删除失败还是不存在该题目，如果删除失败抛出异常即可，不存在就继续进行，尝试删除样例
        if(r==0){
            long count=questionMapper.selectCount(new QueryWrapper<Question>().eq("question_id",id));
            if(count!=0)throw new BizException(QuestionErrorCode.QUESTION_DELETE_FAILED);
        }
        try{
            testCaseMapper.deleteByQuestionId(id);
        }catch (Exception e){
            log.error("小组自建题目样例删除失败，题目id为{}",id,e);
        }
    }
}
