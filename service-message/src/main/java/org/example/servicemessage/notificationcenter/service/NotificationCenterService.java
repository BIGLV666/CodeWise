package org.example.servicemessage.notificationcenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.enums.NotificationCenterType;
import org.example.servicecommon.until.UserContext;
import org.example.servicemessage.notificationcenter.entry.NotificationCenter;
import org.example.servicemessage.notificationcenter.mapper.NotificationCenterMapper;
import org.example.servicemessage.notificationcenter.vo.HomeNotificationCenterVo;
import org.example.servicemessage.notificationcenter.vo.NotificationCursorPageVo;
import org.example.servicemessage.notificationcenter.vo.NotificationDetailVo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotificationCenterService {
    private final NotificationCenterMapper notificationCenterMapper;
    private final ObjectMapper objectMapper;

    public NotificationCenterService(NotificationCenterMapper notificationCenterMapper, ObjectMapper objectMapper) {
        this.notificationCenterMapper = notificationCenterMapper;
        this.objectMapper = objectMapper;
    }

    /** 按通知 ID 倒序查询当前用户的通知。 */
    public NotificationCursorPageVo listNotifications(Long lastId, int pageSize,
                                                       NotificationCenterType type, boolean unreadOnly) {
        LambdaQueryWrapper<NotificationCenter> wrapper = new LambdaQueryWrapper<NotificationCenter>()
                .eq(NotificationCenter::getUserId, currentUserId())
                .eq(NotificationCenter::getIsDeleted, 0)
                .lt(lastId != null, NotificationCenter::getNotificationCenterId, lastId)
                .eq(type != null, NotificationCenter::getType, type)
                .eq(unreadOnly, NotificationCenter::getIsRead, 0)
                .orderByDesc(NotificationCenter::getNotificationCenterId)
                .last("limit " + (pageSize + 1));

        List<NotificationCenter> notifications = notificationCenterMapper.selectList(wrapper);
        boolean hasNext = notifications.size() > pageSize;
        List<NotificationCenter> pageRecords = hasNext ? notifications.subList(0, pageSize) : notifications;
        List<HomeNotificationCenterVo> records = pageRecords.stream().map(this::toVo).toList();
        String nextCursor = records.isEmpty() ? null
                : records.get(records.size() - 1).getNotificationCenterId();

        return NotificationCursorPageVo.builder()
                .records(records)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    public Long getUnreadCount() {
        return notificationCenterMapper.selectCount(new LambdaQueryWrapper<NotificationCenter>()
                .eq(NotificationCenter::getUserId, currentUserId())
                .eq(NotificationCenter::getIsRead, 0)
                .eq(NotificationCenter::getIsDeleted, 0));
    }

    public NotificationDetailVo getNotificationById(Long notificationId) {
        NotificationCenter notification = notificationCenterMapper.selectOne(
                ownedNotification(notificationId)
        );
        if (notification == null) {
            throw new IllegalArgumentException("通知不存在或已删除");
        }
        if (notification.getIsRead() == 0) {
            notificationCenterMapper.update(null,
                    new LambdaUpdateWrapper<NotificationCenter>()
                            .eq(NotificationCenter::getNotificationCenterId, notificationId)
                            .eq(NotificationCenter::getUserId, currentUserId())
                            .eq(NotificationCenter::getIsDeleted, 0)
                            .set(NotificationCenter::getIsRead, 1));
            notification.setIsRead(1);
        }
        return new NotificationDetailVo(notification, parseExtraData(notification.getExtraData()));
    }

    public void markAsRead(Long notificationId) {
        int updated = notificationCenterMapper.update(null,
                new LambdaUpdateWrapper<NotificationCenter>()
                        .eq(NotificationCenter::getNotificationCenterId, notificationId)
                        .eq(NotificationCenter::getUserId, currentUserId())
                        .eq(NotificationCenter::getIsDeleted, 0)
                        .set(NotificationCenter::getIsRead, 1));
        if (updated == 0) {
            throw new IllegalArgumentException("通知不存在或已删除");
        }
    }

    public void markAllAsRead(NotificationCenterType type) {
        notificationCenterMapper.update(null,
                new LambdaUpdateWrapper<NotificationCenter>()
                        .eq(NotificationCenter::getUserId, currentUserId())
                        .eq(NotificationCenter::getIsDeleted, 0)
                        .eq(NotificationCenter::getIsRead, 0)
                        .eq(type != null, NotificationCenter::getType, type)
                        .set(NotificationCenter::getIsRead, 1));
    }

    public void deleteNotification(Long notificationId) {
        int updated = notificationCenterMapper.update(null,
                new LambdaUpdateWrapper<NotificationCenter>()
                        .eq(NotificationCenter::getNotificationCenterId, notificationId)
                        .eq(NotificationCenter::getUserId, currentUserId())
                        .eq(NotificationCenter::getIsDeleted, 0)
                        .set(NotificationCenter::getIsDeleted, 1));
        if (updated == 0) {
            throw new IllegalArgumentException("通知不存在或已删除");
        }
    }

    private LambdaQueryWrapper<NotificationCenter> ownedNotification(Long notificationId) {
        return new LambdaQueryWrapper<NotificationCenter>()
                .eq(NotificationCenter::getNotificationCenterId, notificationId)
                .eq(NotificationCenter::getUserId, currentUserId())
                .eq(NotificationCenter::getIsDeleted, 0);
    }

    private HomeNotificationCenterVo toVo(NotificationCenter notification) {
        return new HomeNotificationCenterVo(notification);
    }

    private JsonNode parseExtraData(String extraData) {
        if (extraData == null || extraData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(extraData);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("通知扩展数据格式错误", e);
        }
    }

    private Long currentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("请登录后查看通知");
        }
        return userId;
    }
}
