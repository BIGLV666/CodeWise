package org.example.servicecommunity.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolutionDto {
    private Long questionId;
    private String solutionTitle;
    private String solutionContent;
    private List<String> tags;
}
