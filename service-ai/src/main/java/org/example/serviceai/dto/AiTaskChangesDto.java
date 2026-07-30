package org.example.serviceai.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiTaskChangesDto {
    private Integer rangeOffset;
    private Integer rangeLength;
    private String text;
    private Integer version;
    private LocalDateTime createTime;
}
