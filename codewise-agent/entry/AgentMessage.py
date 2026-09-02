from datetime import datetime
from sqlalchemy import BigInteger, DateTime, ForeignKey, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from entry.database import Base


class AgentMessage(Base):
    __tablename__ = "agent_message"
    agent_message_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    agent_conversation_id: Mapped[int] = mapped_column(
        BigInteger,
        ForeignKey("agent_conversation.agent_conversation_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    create_time: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    update_time: Mapped[datetime] = mapped_column(
        DateTime,
        nullable=False,
        default=datetime.utcnow,
        onupdate=datetime.utcnow,
    )
