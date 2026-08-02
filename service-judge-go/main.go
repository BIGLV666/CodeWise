package main

import (
	"context"
	"log"
	"os/signal"
	"syscall"

	"github.com/gin-gonic/gin"
	"codewise-judge/internal/config"
	"codewise-judge/internal/handler"
	"codewise-judge/internal/infra"
	"codewise-judge/internal/repository"
	"codewise-judge/internal/service"
)

func main() {
	cfg := config.Load()
	repo, err := repository.OpenMySQL(cfg.MySQLDSN)
	if err != nil { log.Fatal(err) }
	defer repo.DB.Close()
	redisStore, err := infra.OpenRedis(context.Background(), cfg.RedisAddr, cfg.RedisPassword)
	if err != nil { log.Fatal(err) }
	defer redisStore.Client.Close()

	judgeService := &service.Judge{Repo: repo}
	httpHandler := &handler.HTTP{Judge: judgeService}
	engine := gin.Default()
	httpHandler.Register(engine)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if cfg.MQEnabled {
		go func() {
			rabbit := &infra.Rabbit{URL: cfg.RabbitURL, Queue: cfg.JudgeQueue, Exchange: cfg.JudgeExchange, RoutingKey: cfg.QuestionSubmitRoutingKey, Judge: judgeService}
			if err := rabbit.Consume(ctx); err != nil { log.Printf("rabbit consumer stopped: %v", err) }
		}()
	} else {
		log.Printf("rabbit consumer disabled; set JUDGE_MQ_ENABLED=true after debug flow is migrated")
	}

	log.Printf("go judge listening on :%s", cfg.HTTPPort)
	if err := engine.Run(":" + cfg.HTTPPort); err != nil { log.Fatal(err) }
}
