package org.example.servicemessage.notificationcenter.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationCursorPageVo {
    private List<HomeNotificationCenterVo> records;
    private String nextCursor;
    private Boolean hasNext;
}
