package org.example.servicereview.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class ProgressTrackerDto {
    private Long questionId;
    private String notesContent;
    private LocalDate beginTime;
}
