package org.example.servicecommon.dto;

import lombok.Data;

@Data
public class ReviewJudgeRecordDto {
    private Long userId;
    private Long questionId;
    private String questionTitle;
    private Long submitRecordId;
    private Long judgeRecordId;
    private Integer acTestTotal;
    private Integer allTestTotal;
    private String status;
    private String errorMessage;


}
