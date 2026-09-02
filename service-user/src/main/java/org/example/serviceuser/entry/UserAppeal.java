package org.example.serviceuser.entry;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Getter;

import java.time.LocalDateTime;

@Data
@TableName("user_appeal")
public class UserAppeal {
    @TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
    private Long userAppealId;
    /**
     * 申诉人id
     */
    private Long userId;
    /**
     * 申诉原因
     */
    private String appealReason;
    /**
     * 附件发送的邮箱
     */
    private String reasonEmail;

    /**
     * 申诉状态 0-未处理 1-已处理
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
    /**
     * 处理结果
     */
    private String processResult;
    /**
     * 处理人id
     */
    private Long operatorId;

}
