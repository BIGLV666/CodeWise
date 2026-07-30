package org.example.serviceai.dto;

import lombok.Data;

import java.util.List;

@Data
public class AiTaskDto {
    private String rootId;
    private Long userId;
    private Long questionId;

    private String code;
    private List<AiTaskChangesDto>  changes;

}
