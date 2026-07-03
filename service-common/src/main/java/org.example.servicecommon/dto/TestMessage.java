package org.example.servicecommon.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
public class TestMessage implements Serializable {
    private static final long serialVersionUID = 1L;


    private Long questionId;

    private String inputData;

    private String expectedOutput;


    private Integer timeLimit;

    private Integer memoryLimit;

    private Long createUserId;

    private Integer sortOrder = 0;


    private Integer scoreWeight = 100;


}
