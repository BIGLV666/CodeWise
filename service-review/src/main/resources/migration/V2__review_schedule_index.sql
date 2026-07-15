ALTER TABLE review
    ADD INDEX idx_review_schedule (user_id, status, next_review_time);
