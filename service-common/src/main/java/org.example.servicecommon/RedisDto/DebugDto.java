package org.example.servicecommon.RedisDto;

import lombok.Data;

import java.util.List;

@Data
public class DebugDto {
    private Long userId;
    private String code;
    private String language;
    private Long questionId;
    private List<GetDebugTestDto>tests;
}
