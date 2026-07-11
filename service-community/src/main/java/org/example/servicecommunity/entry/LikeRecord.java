package org.example.servicecommunity.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LikeRecord {
    @TableId(type = IdType.AUTO)
    private Long likeRecordId;
    private Long userId;
    private Long postId;
    private String type;
}
