# CodeWise Go Judge

这是 Java `service-judge` 的 Go 重构模块，职责对应关系如下：

```text
Gin Handler / Rabbit Consumer
        ↓
Judge Service
        ↓
MySQL Repository + Java Executor
        ↓
javac 一次编译，Java Main 按测试点执行
```

## 当前迁移范围

- `GET /health`：服务健康检查
- `POST /api/judge/execute`：本地调试单个 Java 程序
- `POST /api/judge/submit/:id`：从 MySQL 读取提交和 ACM 测试点并判题
- RabbitMQ 提交消费者：默认关闭，避免函数调试消息被误消费
- Redis：启动时连接并校验，调试状态存储接口已预留
- 函数模式和 Docker 沙箱：暂未切换，先保留 Java 服务承载

## 启动

```powershell
go mod tidy
$env:JUDGE_MYSQL_DSN='root:password@tcp(127.0.0.1:3306)/codewise?charset=utf8mb4&parseTime=true'
go run .
```

启动前必须确保 MySQL 和 Redis 可连接。RabbitMQ 迁移完成后再设置：

```powershell
$env:JUDGE_MQ_ENABLED='true'
```

当前执行器使用本机 `javac` 和 `java`，仅用于学习和本地验证，不要直接暴露给不可信用户。
