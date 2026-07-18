package org.example.servicequestion.FeugnService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.serviceapi.dto.question.QuestionDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.mapper.QuestionMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class QuestionServiceFeign {

    public QuestionServiceFeign(QuestionMapper questionMapper) {
        this.questionMapper = questionMapper;
    }
    private final QuestionMapper questionMapper;

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
    
    private QuestionDto ToQuestionDto(Question question) {
        return QuestionDto.builder().questionId(question.getQuestionId()).title(question.getTitle()).
               description(question.getDescription()).inputDesc(question.getInputDesc()).outputDesc(question.getOutputDesc())
               .sampleInput(question.getSampleInput()).sampleOutput(question.getSampleOutput()).hint(question.getHint()).source(question.getSource()).difficulty(question.getDifficulty()).tags(question.getTags()).
               timeLimit(question.getTimeLimit()).memoryLimit(question.getMemoryLimit()).status(question.getStatus()).aiStatue(question.getAiStatue()).createUserId(question.getCreateUserId()).createTime(question.getCreateTime())
               .updateTime(question.getUpdateTime()).totalSubmit(question.getTotalSubmit()).totalAc(question.getTotalAc()).passRate(question.getPassRate()).contentHash(question.getContentHash()).build();
    }

}
