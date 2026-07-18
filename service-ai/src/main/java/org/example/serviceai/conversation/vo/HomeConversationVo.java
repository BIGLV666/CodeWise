package org.example.serviceai.conversation.vo;

import lombok.Data;
import org.example.serviceai.entry.Conversation;

@Data
public class HomeConversationVo {
    private Long conversationId;
    private Long questionId;
    private Long userId;
    private String conversationName;
    public HomeConversationVo(Conversation v){
        this.conversationId = v.getConversationId();
        this.questionId = v.getQuestionId();
        this.userId = v.getUserId();
        this.conversationName = v.getConversationName();
    }
}
