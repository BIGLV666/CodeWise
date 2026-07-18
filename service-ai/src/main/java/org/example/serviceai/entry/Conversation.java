package org.example.serviceai.entry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@TableName("ai_conversation")
public class Conversation {
    @TableId(type = IdType.AUTO)
    private Long conversationId;
    private String conversationName;
    private String messageId;
    private Long userId;
    private Long submitId;
    private Long questionId;
    private String questionContent;
    private String code;
    private String log;
    private String inputData;
    private String expectedOutput;
    private String userOutput;
    private String status;
    private String language;
    private LocalDateTime createTime;

}
