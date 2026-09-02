from pydantic import BaseModel, Field


class CallAiRequest(BaseModel):
    user_config_id: int
    conversation_id: int
    question: str = Field(min_length=1, max_length=10000)
    model_name: str | None = None
