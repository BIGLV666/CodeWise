from datetime import datetime
from typing import List

from sqlalchemy.orm import Session

from entry.AgentMessage import AgentMessage
from entry.Conversrtion import AgentConversation
from mapper import AgentConversationMapper, AgentMessageMapper


class ConversationService:
    def __init__(self, session: Session):
        self.session = session
        self.agent_conversation_mapper = AgentConversationMapper(session)
        self.agent_message_mapper = AgentMessageMapper(session)

    def get_agent_conversation(self, user_id: int) -> List[AgentConversation]:
        """获取指定用户的所有对话记录。"""
        return self.agent_conversation_mapper.get_agent_conversations(user_id)

    def get_conversation_messages(self, conversation_id: int, user_id: int) -> List[AgentMessage]:
        """获取指定对话的所有消息记录。"""
        return self.agent_message_mapper.get_all_agent_messages(conversation_id, user_id)
    def create_conversation(self, user_id: int) -> AgentConversation:
        """创建新的对话记录。"""
        new_conversation = AgentConversation(
            user_id=user_id,
            agent_conversation_name="新对话",
            create_time=datetime.now(),
            update_time=datetime.now()
            
        )
        return self.agent_conversation_mapper.add_agent_conversation(new_conversation)
    def update_conversation_name(self, conversation_id: int, user_id: int, new_name: str) -> bool:
        """更新指定对话的名称。"""
        rows_updated = self.agent_conversation_mapper.update_agent_conversation_name(new_name, conversation_id, user_id)
        if rows_updated == 0:
            raise LookupError("对话不存在或不属于当前用户")
        # 返回更新后的对话记录
        return rows_updated != 0
    def delete_conversation(self, conversation_id: int, user_id: int) -> bool:
        """删除指定对话及其所有消息记录。"""
        rows_deleted = self.agent_conversation_mapper.delete_agent_conversation(conversation_id, user_id)
        if rows_deleted == 0:
            raise LookupError("对话不存在或不属于当前用户")
        # 返回删除的对话记录
        return rows_deleted != 0