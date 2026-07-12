package org.example.servicecommunity.entry;

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
@TableName("solution")
public class Solution {
    @TableId(type = IdType.AUTO)
    private Long solutionId;
    private Long questionId;
    private String solutionTitle;
    private String solutionContent;
    private Long solutionUserId;
    @Builder.Default
    private Long likeCount = 0L;
    @Builder.Default
    private Long lookCount = 0L;
    @Builder.Default
    private Long commentCount = 0L;
    /** 0 待审核，1 正常，2 下架。 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

}
