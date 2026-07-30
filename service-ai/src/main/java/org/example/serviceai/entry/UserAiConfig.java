package org.example.serviceai.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceai.dto.UserAiConfigDto;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "user_ai_config", autoResultMap = true)
public class UserAiConfig {
    @TableId(type = IdType.AUTO)
    private Long userAiConfigId;
    private Long userId;
    private String groupName;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> modelNames;
    private String aiUrl;
    private String apiKey;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    public UserAiConfig (UserAiConfigDto userAiConfigDto) {
        this.groupName = userAiConfigDto.getGroupName();
        this.modelNames = userAiConfigDto.getModelNames();
        this.aiUrl = userAiConfigDto.getAiUrl();
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }
}
