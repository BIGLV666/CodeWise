package org.example.servicequestion.FeugnService;

import org.example.serviceapi.dto.QuestionDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.service.QuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestParam;

@Service
public class QuestionServiceFeign {

    @Autowired
    private QuestionMapper questionMapper;
    public QuestionDto getQuestionById( Long id) {
        Question question = questionMapper.selectById(id);
        QuestionDto questionDto = QuestionDto.builder().questionId(question.getQuestionId()).title(question.getTitle()).
                description(question.getDescription()).inputDesc(question.getInputDesc()).outputDesc(question.getOutputDesc())
                .sampleInput(question.getSampleInput()).sampleOutput(question.getSampleOutput()).hint(question.getHint()).source(question.getSource()).difficulty(question.getDifficulty()).tags(question.getTags()).
                timeLimit(question.getTimeLimit()).memoryLimit(question.getMemoryLimit()).status(question.getStatus()).aiStatue(question.getAiStatue()).createUserId(question.getCreateUserId()).createTime(question.getCreateTime())
                .updateTime(question.getUpdateTime()).totalSubmit(question.getTotalSubmit()).totalAc(question.getTotalAc()).passRate(question.getPassRate()).contentHash(question.getContentHash()).build();
        return questionDto;
    }
}
