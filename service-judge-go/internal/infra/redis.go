package infra

import (
	"context"

	"github.com/redis/go-redis/v9"
)

type Redis struct { Client *redis.Client }

func OpenRedis(ctx context.Context, address, password string) (*Redis, error) {
	client := redis.NewClient(&redis.Options{Addr: address, Password: password, DB: 0})
	if err := client.Ping(ctx).Err(); err != nil {
		client.Close()
		return nil, err
	}
	return &Redis{Client: client}, nil
}

func (store *Redis) SetStatus(ctx context.Context, key, status string) error {
	return store.Client.Set(ctx, key, status, 0).Err()
}
