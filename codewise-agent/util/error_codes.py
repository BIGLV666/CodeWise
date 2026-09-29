from enum import Enum


class AgentErrorCode(Enum):
    """Agent 侧错误码（服务号 10），编码规则与 Java 侧 CommonErrorCode 一致：
    ``类别(3位) * 10000 + 服务号(2位) * 100 + 序号(2位)``。

    面向用户的文案只能来自本枚举；原始异常文本（含 JWT 库、数据库、dsh 运行时的报错）
    一律只写日志，不进响应体。
    """

    MISSING_TOKEN = (4011001, "请先登录后再使用对话功能")
    TOKEN_INVALID = (4011002, "登录状态已失效，请重新登录")
    CONVERSATION_NOT_FOUND = (4041001, "对话不存在或已被删除")
    AGENT_CALL_FAILED = (4001001, "这次回复没有成功，请稍后重试")
    INTERNAL_ERROR = (5001001, "服务繁忙，请稍后重试")

    def __init__(self, code: int, message: str):
        self.code = code
        self.message = message
