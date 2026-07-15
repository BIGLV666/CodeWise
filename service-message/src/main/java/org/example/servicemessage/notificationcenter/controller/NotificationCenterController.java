package org.example.servicemessage.notificationcenter.controller;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.servicemessage.notificationcenter.service.NotificationCenterService;
import org.example.servicemessage.notificationcenter.vo.NotificationCursorPageVo;
import org.example.servicemessage.notificationcenter.vo.NotificationDetailVo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/message/notifications")
public class NotificationCenterController {
    private final NotificationCenterService notificationCenterService;

    public NotificationCenterController(NotificationCenterService notificationCenterService) {
        this.notificationCenterService = notificationCenterService;
    }

    /**
     * 游标分页查询当前用户的通知。首次不传 lastId，下一页传上次返回的 nextCursor。
     * type 可传 LIKE 或 REVIEW；unreadOnly=true 时仅返回未读通知。
     */
    @GetMapping
    public Result<NotificationCursorPageVo> listNotifications(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) NotificationCenterType type,
            @RequestParam(defaultValue = "false") Boolean unreadOnly) {
        validatePageSize(pageSize);
        return Result.success(notificationCenterService.listNotifications(
                lastId, pageSize, type, Boolean.TRUE.equals(unreadOnly)
        ));
    }

    /** 查询当前用户未删除通知的未读总数。 */
    @GetMapping("/unread-count")
    public Result<Long> getUnreadCount() {
        return Result.success(notificationCenterService.getUnreadCount());
    }

    /** 查询通知详情并自动标记为已读，只允许查看自己的未删除通知。 */
    @GetMapping("/{notificationId}")
    public Result<NotificationDetailVo> getNotification(@PathVariable Long notificationId) {
        return Result.success(notificationCenterService.getNotificationById(notificationId));
    }

    /** 将当前用户的一条通知标记为已读。 */
    @PutMapping("/{notificationId}/read")
    public Result<String> markAsRead(@PathVariable Long notificationId) {
        notificationCenterService.markAsRead(notificationId);
        return Result.success("已标记为已读");
    }

    /** 将当前用户的通知全部标记为已读；传 type 时只处理该类型。 */
    @PutMapping("/read-all")
    public Result<String> markAllAsRead(@RequestParam(required = false) NotificationCenterType type) {
        notificationCenterService.markAllAsRead(type);
        return Result.success("已全部标记为已读");
    }

    /** 软删除当前用户的一条通知，删除后列表和详情均不再返回。 */
    @DeleteMapping("/{notificationId}")
    public Result<String> deleteNotification(@PathVariable Long notificationId) {
        notificationCenterService.deleteNotification(notificationId);
        return Result.success("删除成功");
    }

    private void validatePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize 必须在 1 到 100 之间");
        }
    }
}
