# main.py
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.AgentApi import router as agent_router
from api.CallAiApi import router as call_ai_router

app = FastAPI(title="CodeWise Agent")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(call_ai_router)
app.include_router(agent_router)
