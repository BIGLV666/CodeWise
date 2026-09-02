from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session

from entry.Conversrtion import AgentConversation


class AgentConversationMapper:
    def __init__(self, session: Session) -> None:
        self.session = session
    def get_agent_conversations(self, user_id: int) -> list[AgentConversation]:
        stmt = select(AgentConversation).where(AgentConversation.user_id == user_id).order_by(AgentConversation.create_time.desc())
        return list(self.session.scalars(stmt).all())
    def add_agent_conversation(self, agent_conversation: AgentConversation) -> AgentConversation:
        self.session.add(agent_conversation)
        self.session.flush()
        return agent_conversation
    def get_agent_conversation(self, agent_conversation_id: int,user_id: int) -> AgentConversation:
        stmt = select(AgentConversation).where(AgentConversation.agent_conversation_id == agent_conversation_id, AgentConversation.user_id == user_id)
        agent_conversation = self.session.scalars(stmt).first()        
        return agent_conversation
    def update_agent_conversation_name(self,name: str,agent_conversation_id: int,user_id: int) -> int:
        stmt = update(AgentConversation).where(AgentConversation.agent_conversation_id == agent_conversation_id, AgentConversation.user_id == user_id).values(agent_conversation_name=name)
        rows = self.session.execute(stmt).rowcount
        return rows
    def delete_agent_conversation(self, agent_conversation_id: int,user_id: int) -> int:
        stmt = delete(AgentConversation).where(AgentConversation.agent_conversation_id == agent_conversation_id, AgentConversation.user_id == user_id)
        rows = self.session.execute(stmt).rowcount
        return rows
