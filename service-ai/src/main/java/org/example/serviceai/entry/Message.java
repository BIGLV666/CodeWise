package org.example.serviceai.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceai.conversation.enums.Role;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("ai_message")
public class Message {
    @TableId(type = IdType.AUTO)
    private Long messageId;
    private Long conversationId;
    private String content;
    private Long userId;
    private String currentCode;
    private Role role;
    private LocalDateTime createTime;

}
