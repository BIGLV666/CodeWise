from dataclasses import dataclass

@dataclass
class CallAiDto:
    user_config_id: int
    user_id: int
    question: str
    conversation_id: int
    model_name: str | None = None
