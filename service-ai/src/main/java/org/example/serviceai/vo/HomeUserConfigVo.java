package org.example.serviceai.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.example.serviceai.entry.UserAiConfig;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor

public class HomeUserConfigVo {
    private Long userConfigId;
    private List<String>modelNames;
    private String groupName;
    public  HomeUserConfigVo(UserAiConfig userAiConfig) {
        this.userConfigId=userAiConfig.getUserAiConfigId();
        this.modelNames=userAiConfig.getModelNames();
        this.groupName=userAiConfig.getGroupName();
    }
}
