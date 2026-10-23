package com.example.servicequiznest.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@Builder
@AllArgsConstructor
@TableName("group_question")
/*
  TODO root用户退出小组后假如小组无人会删除小组，那么该小组的题目会变孤儿数据,所以退出时候需要发送mq消息，清理该小组的资产
  */
public class GroupQuestion {
    @TableId(type = IdType.AUTO)
    private Long groupQuestionId;
    //对应题目的id
    private Long questionId;
    //创建人id
    private Long createUserId;
    //所属小组id
    private Long groupId;
    /**
     * 是否为平台题目，1 是 0不是，使用int便于后续拓展
     */
    private Integer isPlatFromQuestion;


    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    public GroupQuestion() {}



}
