package org.example.servicequestion.entry;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("test_case")
public class TestCase {

    @TableId(type = IdType.AUTO)
    private Long caseId;

    private Long questionId;

    private String inputData;

    private String expectedOutput;

    @Builder.Default
    private Integer isSample = 0;

    @Builder.Default
    private Integer isHidden = 1;

    @Builder.Default
    private Integer sortOrder = 0;

    @Builder.Default
    private Integer scoreWeight = 100;

    private Integer timeLimit;

    private Integer memoryLimit;

    private Long createUserId=  2070815253393932289L;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime=LocalDateTime.now();

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime=LocalDateTime.now();
}
