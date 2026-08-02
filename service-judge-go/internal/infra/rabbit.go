package infra

import (
	"context"
	"encoding/json"
	"log"

	amqp "github.com/rabbitmq/amqp091-go"
	"codewise-judge/internal/service"
)

type Rabbit struct { URL, Queue, Exchange, RoutingKey string; Judge *service.Judge }

func (rabbit *Rabbit) Consume(ctx context.Context) error {
	connection, err := amqp.Dial(rabbit.URL)
	if err != nil { return err }
	defer connection.Close()
	channel, err := connection.Channel()
	if err != nil { return err }
	defer channel.Close()
	if err := channel.Qos(1, 0, false); err != nil { return err }
	messages, err := channel.Consume(rabbit.Queue, "", false, false, false, false, nil)
	if err != nil { return err }

	for {
		select {
		case <-ctx.Done(): return nil
		case message, ok := <-messages:
			if !ok { return nil }
			var submitID int64
			if err := json.Unmarshal(message.Body, &submitID); err != nil {
				_ = message.Reject(false); continue
			}
			recordID, _, err := rabbit.Judge.Submit(ctx, submitID)
			if err != nil { log.Printf("judge submit %d failed: %v", submitID, err); _ = message.Reject(false); continue }
			body, _ := json.Marshal(recordID)
			_ = channel.Publish(rabbit.Exchange, rabbit.RoutingKey, false, false, amqp.Publishing{ContentType: "application/json", Body: body})
			_ = message.Ack(false)
		}
	}
}
