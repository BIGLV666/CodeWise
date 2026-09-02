package org.example.serviceuser.enums;

import lombok.Getter;

@Getter
public enum UserAppealStatus {
    UNPROCESSED(0, "未处理"),
    PROCESSED(1, "已处理");

    private final int code;
    private final String description;

    UserAppealStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

}
