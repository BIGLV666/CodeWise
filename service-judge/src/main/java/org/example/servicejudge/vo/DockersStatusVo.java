package org.example.servicejudge.vo;

import lombok.Data;

import java.util.List;

@Data
public class DockersStatusVo {
    private String language;
    private Integer total;
    private Integer idle;
    private Integer busy;
    private Integer waiting;
    private Integer configuredCapacity;
    private List<String> idleContainerIds;
    private List<String> busyContainerIds;
}
