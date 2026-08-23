package org.example.serviceapi.enums;

import lombok.Getter;
import lombok.Setter;
@Getter
public enum NotificationCenterType {
    LIKE,
    REVIEW,
    AI_ADVICE,
    CHECKED,
    APPEAL,
    /** 复习掌握祝贺（review -> message，extraData 为 ReviewMasteredDto） */
    REVIEW_MASTERED
}
