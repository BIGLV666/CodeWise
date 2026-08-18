package org.example.servicecommunity.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.example.servicecommunity.enums.PostType;

import java.time.LocalDateTime;

@Data
@TableName("take_down_post")
public class TakeDownPost {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    
    private PostType rootType;
    
    private Long rootId;
    
    private Long rootCommentId;
    
    private Long questionId;
    
    private String reason;
    
    private Long adminId;
    
    private LocalDateTime createTime;
}
