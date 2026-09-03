package org.example.servicereview.entry;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.example.servicereview.dto.ProgressTrackerDto;
import org.example.servicereview.enums.ProgressTrackerStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.chrono.ChronoLocalDate;
import java.util.List;

/**
 * 进度计划追踪,用于用户自定义题单并按照日期学习跟进
 */
@Data
// autoResultMap：submit_ids 为 JSON 列，读取时必须经 JacksonTypeHandler 反序列化，
// 缺失该属性时内置 select 方法的结果映射不走路由 handler，submitIds 会读不出来。
@TableName(value = "progress_tracker", autoResultMap = true)
public class ProgressTracker {
    @TableId(type = com.baomidou.mybatisplus.annotation.IdType.AUTO)
    private Long progressId;
    private Long userId;
    private Long questionId;
    /**
     * 只有当存在提交记录时才会有值，表示该题单的所有提交记录id集合，仅限当天添加
     * 同时只有AC记录存在时才会标注为已完成
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long>submitIds;
    /**
     * 计划状态
     */
    private Integer status;
    /**
     * 备注，用于用户自定义题单的学习计划描述
     */
    private String notesContent;
    /**
     * 题目做完后的反思等
     */
    private String summaryContent;
    /**
     * 题目开始时间
     * <p>注意：当前时间超过开始时间不允许修改开始时间</p>
     */
    private LocalDate beginTime;
    /**
     * 创建时间
     */
    private LocalDateTime createTime;
    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    public ProgressTracker() {
    }
    public ProgressTracker(ProgressTrackerDto dto) {
        if(dto.getQuestionId()==null){
            throw new IllegalArgumentException("题目不可为空");
        }
        if(dto.getBeginTime()==null){
            throw new IllegalArgumentException("开始时间不可为空");
        }
        // 计划只允许从今天或未来开始（单向状态机入口约束）：
        // 早于今天的日期创建后即「已过期」，且过期不可逆向，故直接拒绝
        if(dto.getBeginTime().isBefore(LocalDate.now())){
            throw new IllegalArgumentException("开始时间不可早于当前时间");
        }
        this.questionId=dto.getQuestionId();
        this.notesContent=dto.getNotesContent();
        this.beginTime=dto.getBeginTime();
        this.updateTime=LocalDateTime.now();
    }


}
