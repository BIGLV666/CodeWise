package org.example.servicereview.entry;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("favorites")
public class Favorites {
    @TableId(type = IdType.AUTO)
    private Long favoritesId;
    private String favoritesName;
    private String favoritesType;
    private String favoritesContent;
    private Long userId;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Long> questionIds;

    private LocalDateTime createTime;

    private LocalDateTime updateTime=LocalDateTime.now();
}
