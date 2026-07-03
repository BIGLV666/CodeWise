package org.example.servicequestion.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class InsertQuestionDto {


    private String title;
    private String description;
    private String inputDesc;
    private String outputDesc;
    private String sampleInput;
    private String sampleOutput;

    private String hint;//提示


    private String tags;//标签

    private Integer timeLimit;
    private Integer memoryLimit;



}
