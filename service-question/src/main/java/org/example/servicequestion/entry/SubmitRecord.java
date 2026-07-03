package org.example.servicequestion.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("submit_record")
public class SubmitRecord {
    @TableId(type = IdType.AUTO)
    private Long submitRecordId;
    private Long questionId;
    private Long userId;
    private LocalDateTime submitTime;
    private String submitContent;
    private String submitStatus;
    private Integer timeUsed;
    private Integer memoryUsed;
    private String JudgeStatus;
    private String language;
    private LocalDateTime createTime=LocalDateTime.now();
}