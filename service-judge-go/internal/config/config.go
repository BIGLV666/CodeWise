package config

import (
	"os"
	"strconv"
)

type Config struct {
	HTTPPort       string
	MySQLDSN       string
	RedisAddr      string
	RedisPassword  string
	RabbitURL      string
	JudgeQueue     string
	JudgeExchange  string
	QuestionSubmitRoutingKey string
	MQEnabled       bool
	DefaultTimeLimit int
}

func Load() Config {
	return Config{
		HTTPPort:       env("JUDGE_HTTP_PORT", "8087"),
		MySQLDSN:       env("JUDGE_MYSQL_DSN", "root:root@tcp(127.0.0.1:3306)/codewise?charset=utf8mb4&parseTime=true"),
		RedisAddr:      env("JUDGE_REDIS_ADDR", "127.0.0.1:6379"),
		RedisPassword:  os.Getenv("JUDGE_REDIS_PASSWORD"),
		RabbitURL:      env("JUDGE_RABBIT_URL", "amqp://guest:guest@127.0.0.1:5672/"),
		JudgeQueue:     env("JUDGE_QUEUE", "judge.queue"),
		JudgeExchange:  env("JUDGE_RESULT_EXCHANGE", "question.exchange"),
		QuestionSubmitRoutingKey: env("JUDGE_RESULT_ROUTING_KEY", "question.submit.record.routing"),
		MQEnabled:       env("JUDGE_MQ_ENABLED", "false") == "true",
		DefaultTimeLimit: envInt("JUDGE_TIME_LIMIT_MS", 2000),
	}
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) int {
	value, err := strconv.Atoi(env(key, ""))
	if err != nil || value <= 0 {
		return fallback
	}
	return value
}
