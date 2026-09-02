# api/AgentApi.py
from fastapi import APIRouter, Depends, HTTPException


from dto.Result import Result
from service.Conversation import ConversationService
from util.jwt import get_current_user_id, get_token_from_header

from entry.database import get_db


router = APIRouter(prefix="/api/agent", tags=["Agent"])

class AgentApi:
    @router.get("/agent_conversation")
    def get_agent_conversation(
        user_id: int = Depends(get_current_user_id)):
       try:
            with get_db() as session:
                conversation_service = ConversationService(session)
                conversations = conversation_service.get_agent_conversation(user_id)
                result = Result()
                result.code = 200
                result.message = "success"
                result.data = conversations
                return result
       except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
    @router.put("/conversation/{conversation_id}/name")
    def update_conversation_name(
        conversation_id: int,
        new_name: str,
        user_id: int = Depends(get_current_user_id),
    ):
        try:
            with get_db() as session:
                conversation_service = ConversationService(session)
                conversation_service.update_conversation_name(conversation_id, user_id, new_name)
            return {"code": 200, "message": "success"}
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
    @router.post("/conversation")
    def create_conversation(
        user_id: int = Depends(get_current_user_id),
    ):
        with get_db() as session:
            conversation_service = ConversationService(session)
            new_conversation = conversation_service.create_conversation(user_id)
        return {"code": 200, "message": "success", "data": new_conversation}
    @router.delete("/conversation/{conversation_id}")
    def delete_conversation(
        conversation_id: int,
        user_id: int = Depends(get_current_user_id),
    ):
        try:
            with get_db() as session:
                conversation_service = ConversationService(session)
                conversation_service.delete_conversation(conversation_id, user_id)
            return {"code": 200, "message": "success"}
        except LookupError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
    @router.get("/messages/{conversation_id}")
    def get_conversation_messages(
        conversation_id: int,
        user_id: int = Depends(get_current_user_id),
    ):
        with get_db() as session:
            messages = ConversationService(session).get_conversation_messages(conversation_id, user_id)
        return {"code": 200, "message": "success", "data": messages}
    