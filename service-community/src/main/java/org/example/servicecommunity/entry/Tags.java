package org.example.servicecommunity.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.servicecommunity.enums.PostType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tags {
    @TableId(type = IdType.AUTO)
    private Long tagId;
    private String tagName;
    private Long postId;
    private PostType type;
}
