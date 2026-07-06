package org.example.servicereview.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@TableName(value = "review_record", autoResultMap = true)
public class ReviewRecord {
    @TableId(type = IdType.AUTO)
    private Long reviewRecordId;
    private Long userId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long>pendingReviewQuestionIds;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long>completedReviewQuestionIds;
    private LocalDateTime createTime;
    private Integer reviewDays=0;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> acQuestionIds;
    private LocalDate reviewDate;
}
