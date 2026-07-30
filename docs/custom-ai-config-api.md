# 用户自定义 AI 服务接口

用户可以保存 OpenAI 兼容的 HTTPS API 地址、API Key 和模型列表。API Key 使用 AES-256-GCM 加密后写入数据库，接口只返回固定掩码。

## 环境变量

启动 `service-ai` 前必须配置 32 字节主密钥的 Base64 字符串：

```powershell
$env:API_KEY_MASTER_KEY="<base64-key>"
```

主密钥不能提交到代码仓库，丢失后已有 API Key 无法解密。

## 获取远程模型列表

```http
POST /api/ai/configs/models
Content-Type: application/json
```

```json
{
  "baseUrl": "https://api.example.com/v1",
  "apiKey": "sk-example"
}
```

服务端请求 `GET {baseUrl}/models`。出于 SSRF 防护，只允许 HTTPS 公网地址，不允许本机、局域网和链路本地地址。

## 创建配置

```http
POST /api/ai/configs
Content-Type: application/json
```

```json
{
  "groupName": "个人模型",
  "modelNames": ["model-a", "model-b"],
  "aiUrl": "https://api.example.com/v1",
  "apiKey": "sk-example"
}
```

同一用户的 `groupName` 不能重复。

## 查询配置

```http
GET /api/ai/configs
GET /api/ai/configs/{configId}
```

用户只能查询自己的配置。

## 更新配置

```http
PUT /api/ai/configs/{configId}
```

请求字段与创建接口相同。`apiKey` 为空时保留原 API Key，传入新值时重新加密。

## 删除配置

```http
DELETE /api/ai/configs/{configId}
```

## 接入原问答接口

继续使用原 SSE 接口：

```http
POST /api/ai/advice/ask
```

自动选择平台模型：

```json
{
  "conversationId": 1,
  "question": "为什么这段代码越界？",
  "code": "..."
}
```

使用用户模型：

```json
{
  "conversationId": 1,
  "question": "为什么这段代码越界？",
  "code": "...",
  "userAiConfigId": 12,
  "modelName": "model-a"
}
```

`userAiConfigId` 和 `modelName` 必须同时为空或同时提供。服务端会校验配置归属以及模型是否属于该配置。

## 用户可见异常

- `400`：参数错误、API Key 无效、模型不属于配置。
- `403`：访问了其他用户的配置。
- `429`：远程 AI 服务限流或额度不足。
- `502`：远程 AI 服务异常。
- `500`：服务内部异常。
