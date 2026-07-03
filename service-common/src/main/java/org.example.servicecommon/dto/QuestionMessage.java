package org.example.servicecommon.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
public class QuestionMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long questionId;
    private String title;
    private String description;
    private String inputDesc;
    private String outputDesc;
    private String sampleInput;
    private String sampleOutput;

    private Long createUserId;


    private String hint;//提示

    private String tags;//标签

    private Integer timeLimit;
    private Integer memoryLimit;


}
