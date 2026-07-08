package org.example.servicejudge.entry;

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
@TableName("judge_record")
public class JudgeRecord {
    @TableId(type = IdType.AUTO)
    private Long judgeRecordId;
    private Long submitRecordId;
    private Long testCaseId;
    private String errorMsg;
    private String log;
    private String submitStatus;
    private String userOutput;
    private Integer failIndex;
    private String code;
    private Integer testTotal;

    private String inputData;
    private String expectedOutput;
    private Integer timeUsed;
    private Integer memoryUsed;
    private LocalDateTime createTime=LocalDateTime.now();


}
