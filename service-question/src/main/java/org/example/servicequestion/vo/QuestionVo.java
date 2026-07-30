package org.example.servicequestion.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.servicequestion.entry.FunctionConfig;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.enums.QuestionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class QuestionVo {

    private Long questionId;
    private String title;
    private String description;
    private String inputDesc;
    private String outputDesc;
    private String sampleInput;
    private String sampleOutput;

    private String hint;
    private String source;
    private Integer difficulty;
    private String tags;
    private QuestionType questionType;

    private Integer timeLimit;
    private Integer memoryLimit;

    private Integer status;
    private String aiStatue;
    private Long createUserId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    private Long totalSubmit;
    private Long totalAc;
    private BigDecimal passRate;

    /**
     * 核心函数题配置。
     * 普通 ACM 题目时该字段为 null。
     */
    private FunctionConfig functionConfig;

    /**
     * 构造普通题目详情。
     */
    public QuestionVo(Question question) {
        this(question, null);
    }

    /**
     * 构造题目详情，并携带核心函数配置。
     */
    public QuestionVo(Question question, FunctionConfig functionConfig) {
        if (question == null) {
            throw new IllegalArgumentException("题目信息不能为空");
        }

        this.questionId = question.getQuestionId();
        this.title = question.getTitle();
        this.description = question.getDescription();
        this.inputDesc = question.getInputDesc();
        this.outputDesc = question.getOutputDesc();
        this.sampleInput = question.getSampleInput();
        this.sampleOutput = question.getSampleOutput();

        this.hint = question.getHint();
        this.source = question.getSource();
        this.difficulty = question.getDifficulty();
        this.tags = question.getTags();
        this.questionType = question.getQuestionType();

        this.timeLimit = question.getTimeLimit();
        this.memoryLimit = question.getMemoryLimit();

        this.status = question.getStatus();
        this.aiStatue = question.getAiStatue();
        this.createUserId = question.getCreateUserId();
        this.createTime = question.getCreateTime();
        this.updateTime = question.getUpdateTime();

        this.totalSubmit = question.getTotalSubmit();
        this.totalAc = question.getTotalAc();
        this.passRate = question.getPassRate();

        this.functionConfig = functionConfig;
    }
}
