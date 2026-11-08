package org.example.serviceapi.enums;

import lombok.Getter;

@Getter
public enum WebSocketQueueName {
    INBOX_LIKE,
    INBOX_REVIEW,
    INBOX_CHECKED,
    INBOX_APPEAL,
    INBOX_GROUP_INVITE,
    INBOX_GROUP_BROADCAST,
    INBOX_GROUP_APPROVAL_RESULT,
    AI_ADVICE,
    ;
}
