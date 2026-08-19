package org.example.servicejudge.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("failure_submit")
public class FailureSubmit {
    @TableId(type = IdType.AUTO)
    private Long failureSubmitId;

    private Long submitRecordId;
    /**
     * 状态枚举 0-待处理 1-重试中 2-成功 3-失败
     */
    private Integer status;
    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 上次重试的错误日志
     */
    private String lastError;

    private LocalDateTime createTime;
    private LocalDateTime retryTime;

}
