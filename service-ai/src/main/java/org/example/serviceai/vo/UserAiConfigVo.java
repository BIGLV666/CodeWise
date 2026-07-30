package org.example.serviceai.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceai.dto.UserAiConfigDto;
import org.example.serviceai.entry.UserAiConfig;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAiConfigVo {
    private Long userAiConfigId;
    private String groupName;
    private List<String> modelNames;
    private String aiUrl;
    /**
     * 加密过的apikey,不允许修改
     */
    private String apiKey;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    public  UserAiConfigVo (UserAiConfig userAiConfig) {
        this.userAiConfigId=userAiConfig.getUserAiConfigId();
        this.groupName=userAiConfig.getGroupName();
        this.modelNames=userAiConfig.getModelNames();
        this.aiUrl=userAiConfig.getAiUrl();
        this.apiKey=userAiConfig.getApiKey();
        this.createTime=userAiConfig.getCreateTime();
        this.updateTime=userAiConfig.getUpdateTime();

    }
}
