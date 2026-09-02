from datetime import datetime
from typing import List

from sqlalchemy import DateTime, BigInteger, JSON, String
from sqlalchemy.orm import Mapped, mapped_column

from entry.database import Base


class UserAiConfig(Base):
    __tablename__ = "user_ai_config"

    user_ai_config_id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, nullable=False, index=True)
    group_name: Mapped[str] = mapped_column(String(255), nullable=False)
    model_names: Mapped[List[str]] = mapped_column(JSON, nullable=False, default=list)
    ai_url: Mapped[str] = mapped_column(String(512), nullable=False)
    api_key: Mapped[str] = mapped_column(String(512), nullable=False)
    create_time: Mapped[datetime] = mapped_column(DateTime, nullable=False, default=datetime.utcnow)
    update_time: Mapped[datetime] = mapped_column(
        DateTime,
        nullable=False,
        default=datetime.utcnow,
        onupdate=datetime.utcnow,
    )
