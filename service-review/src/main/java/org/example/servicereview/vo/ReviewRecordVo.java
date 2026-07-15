package org.example.servicereview.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.example.servicereview.entry.ReviewRecord;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ReviewRecordVo {
    private Long reviewRecordId;
    private List<Long>allQuestionIds;
    private List<Long> pendingReviewQuestionIds;

    private List<Long>completedReviewQuestionIds;
    private LocalDateTime createTime;
    private Integer reviewDays;
    private Map<Long,String>allQuestionTitles;
    private List<Long> acQuestionIds;
    private LocalDate reviewDate;
    public ReviewRecordVo(ReviewRecord reviewRecord) {
        this.reviewRecordId=reviewRecord.getReviewRecordId();
        this.acQuestionIds=reviewRecord.getAcQuestionIds();
        this.pendingReviewQuestionIds=reviewRecord.getPendingReviewQuestionIds();
        this.completedReviewQuestionIds=reviewRecord.getCompletedReviewQuestionIds();
        this.createTime=reviewRecord.getCreateTime();
        this.reviewDays=reviewRecord.getReviewDays();
        this.reviewDate=reviewRecord.getReviewDate();
    }
}
