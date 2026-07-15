package org.example.servicereview.dto;

import lombok.Data;

    @Data
    public class ReviewReminderDto {
        private Long userId;
        private Long pendingCount;
    }
