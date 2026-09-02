from sqlalchemy import select
from sqlalchemy.orm import Session

from entry.AgentMessage import AgentMessage


class AgentMessageMapper:
    def __init__(self, session: Session) -> None:
        self.session = session
    def get_all_agent_messages(self, agent_conversation_id: int,user_id: int) -> list[AgentMessage]:
        stmt = select(AgentMessage).where(AgentMessage.agent_conversation_id == agent_conversation_id, AgentMessage.user_id == user_id).order_by(AgentMessage.create_time.desc())
        return list(self.session.scalars(stmt).all())
    def add_agent_message(self, agent_message: AgentMessage) -> AgentMessage:
        self.session.add(agent_message)
        self.session.flush()
        return agent_message
    def get_agent_message_20(self, agent_conversation_id: int,user_id: int) -> list[AgentMessage]:
        stmt = select(AgentMessage).where(AgentMessage.agent_conversation_id == agent_conversation_id, AgentMessage.user_id == user_id).order_by(AgentMessage.create_time.desc()).limit(20)
        messages = list(self.session.scalars(stmt).all())
        messages.reverse()
        return messages

    def get_messages_after_id(
        self,
        agent_conversation_id: int,
        user_id: int,
        message_id: int | None,
    ) -> list[AgentMessage]:
        stmt = select(AgentMessage).where(
            AgentMessage.agent_conversation_id == agent_conversation_id,
            AgentMessage.user_id == user_id,
        )
        if message_id is not None:
            stmt = stmt.where(AgentMessage.agent_message_id > message_id)

        stmt = stmt.order_by(AgentMessage.agent_message_id.asc())
        return list(self.session.scalars(stmt).all())
