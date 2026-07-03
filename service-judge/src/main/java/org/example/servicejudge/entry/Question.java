package org.example.servicejudge.entry;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Question {

    @TableId(type = IdType.AUTO)
    private Long questionId;
    private String title;
    private String description;
    private String inputDesc;
    private String outputDesc;
    private String sampleInput;
    private String sampleOutput;

    private String hint;//提示
    private String source;//题目来源
    private Integer difficulty;//1-简单 2-
    private String tags;//标签

    private Integer timeLimit;
    private Integer memoryLimit;

    private Integer status;//0-下架1-正常2-审核3-私密
    private String aiStatue;//待处理-pending //失败failure success
    private Long createUserId= 2070815253393932289L;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime=LocalDateTime.now();
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime=LocalDateTime.now();

    private Long totalSubmit;
    private Long totalAc;
    private BigDecimal passRate;
    private String contentHash;



}