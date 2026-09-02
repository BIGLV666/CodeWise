from sqlalchemy import select
from sqlalchemy.orm import Session

from entry.UserAiConfig import UserAiConfig


class UserAiConfigMapper:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get_by_id(self, user_ai_config_id: int, user_id: int) -> UserAiConfig:
        config = self.session.scalar(
            select(UserAiConfig).where(
                UserAiConfig.user_ai_config_id == user_ai_config_id,
                UserAiConfig.user_id == user_id,
            )
        )
        if config is None:
            raise LookupError("AI config does not exist or does not belong to the user")
        return config

    def get_by_user_id_and_group_name(
        self,
        user_id: int,
        group_name: str,
    ) -> UserAiConfig | None:
        return self.session.scalar(
            select(UserAiConfig).where(
                UserAiConfig.user_id == user_id,
                UserAiConfig.group_name == group_name,
            )
        )

    def list_by_user_id(self, user_id: int) -> list[UserAiConfig]:
        return list(
            self.session.scalars(
                select(UserAiConfig)
                .where(UserAiConfig.user_id == user_id)
                .order_by(UserAiConfig.update_time.desc())
            )
        )

    def save(self, config: UserAiConfig) -> UserAiConfig:
        self.session.add(config)
        self.session.flush()
        return config

    def delete(self, config: UserAiConfig) -> None:
        self.session.delete(config)
        self.session.flush()
