package org.example.servicequestion.dto;

import lombok.Data;

@Data
public class GetCodeDto {
    private String code;
    private String language;
    private Long questionId;
    private String submitScene;
}
