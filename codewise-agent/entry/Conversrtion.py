from datetime import datetime

from sqlalchemy import BigInteger, DateTime, String
from sqlalchemy.orm import Mapped, mapped_column

from entry.database import Base


class AgentConversation(Base):
    __tablename__ = "agent_conversation"
    agent_conversation_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    agent_conversation_name: Mapped[str] = mapped_column(String(255), nullable=False)
    create_time: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    update_time: Mapped[datetime] = mapped_column(
        DateTime,
        nullable=False,
        default=datetime.utcnow,
        onupdate=datetime.utcnow,
    )
