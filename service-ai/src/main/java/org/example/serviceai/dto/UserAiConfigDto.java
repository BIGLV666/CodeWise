package org.example.serviceai.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserAiConfigDto {
    private String groupName;
    private List<String> modelNames;
    private String aiUrl;
    private String apiKey;
}
