package org.example.servicequestion.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户如果在小组界面提交代码，那么提交记录是小组可见
 * 即该记录除了属于用户本身同样属于小组，该记录不可修改，
 * 即不允许用户删除和修改。
 *
 * <p>当用户退出小组后该记录不会删除；小组解散后的清理需要由 quiz-nest 的
 * 小组生命周期事件驱动，当前尚未接入（记录只增不删）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("group_submit_record")
public class GroupSubmitRecord {
    @TableId(type = IdType.AUTO)
    private Long groupSubmitRecordId;

    /**该记录所对应的判题id*/
    private Long submitRecordId;

    private Long userId;

    private Long groupId;
    private LocalDateTime createTime;

}
