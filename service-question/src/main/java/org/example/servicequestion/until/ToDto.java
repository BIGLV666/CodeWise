package org.example.servicequestion.until;

import lombok.Data;
import org.example.servicecommon.dto.QuestionMessage;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.Question;

@Data
public class ToDto {
    public static QuestionMessage ToQuestionMessage(Question question) {
        QuestionMessage questionMessage = new QuestionMessage();
        questionMessage.setQuestionId(question.getQuestionId());
        questionMessage.setTitle(question.getTitle());
        questionMessage.setDescription(question.getDescription());
        questionMessage.setInputDesc(question.getInputDesc());
        questionMessage.setOutputDesc(question.getOutputDesc());
        questionMessage.setSampleInput(question.getSampleInput());
        questionMessage.setSampleOutput(question.getSampleOutput());
        questionMessage.setCreateUserId(question.getCreateUserId());
        questionMessage.setHint(question.getHint());
        questionMessage.setTags(question.getTags());
        questionMessage.setTimeLimit(question.getTimeLimit());
        questionMessage.setMemoryLimit(question.getMemoryLimit());
        return questionMessage;

    }

}
