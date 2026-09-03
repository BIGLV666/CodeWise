package org.example.servicereview.enums;

import lombok.Getter;

@Getter
public enum ProgressTrackerStatus {
    /**
     * 题目未开始
     */
    NOT_STARTED(0, "未开始"),
    /**
     * 题目进行中
     */
    IN_PROGRESS(1, "进行中"),
    /**
     * 题目已完成
     */
    COMPLETED(2, "已完成"),
    /**
     * 计划已过期：开始时间已过但未完成，可重新规划开始时间（懒加载时判定）
     */
    EXPIRED(3, "已过期");

    private final int code;
    private final String description;

    ProgressTrackerStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
