package org.example.servicequestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.servicequestion.entry.Question;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnQuestionDto {
    private Long questionId;
    private String questionTitle;
    private Integer difficulty;//1-简单 2-
    private String tags;//标签
    private Long totalSubmit;
    private Long totalAc;
    private BigDecimal passRate;
    private Integer status;//0-未尝试1-通过//2尝试过
    public ReturnQuestionDto(Question question) {
        this.questionId=question.getQuestionId();
        this.questionTitle=question.getTitle();
        this.difficulty=question.getDifficulty();
        this.tags=question.getTags();
        this.totalSubmit=question.getTotalSubmit();
        this.totalAc=question.getTotalAc();
        this.passRate=question.getPassRate();
    }
}
