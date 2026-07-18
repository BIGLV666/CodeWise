package org.example.serviceai.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConversationMemory {
    @TableId(type = IdType.AUTO)
    private Long aiConversationMemoryId;
    private Long userId;
    private Long conversationId;
    private String summary;
    private Integer summaryCharsCount;
    private Long endMessageId;
    @Version
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
