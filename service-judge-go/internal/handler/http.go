package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"
	"codewise-judge/internal/model"
	"codewise-judge/internal/service"
)

type HTTP struct { Judge *service.Judge }

func (handler *HTTP) Register(engine *gin.Engine) {
	engine.GET("/health", func(ctx *gin.Context) { ctx.JSON(http.StatusOK, gin.H{"status": "ok"}) })
	engine.POST("/api/judge/execute", handler.execute)
	engine.POST("/api/judge/submit/:id", handler.submit)
}

func (handler *HTTP) execute(ctx *gin.Context) {
	var request model.ExecuteRequest
	if err := ctx.ShouldBindJSON(&request); err != nil { ctx.JSON(400, gin.H{"message": err.Error()}); return }
	ctx.JSON(http.StatusOK, handler.Judge.ExecuteOne(ctx, request))
}

func (handler *HTTP) submit(ctx *gin.Context) {
	id, err := strconv.ParseInt(ctx.Param("id"), 10, 64)
	if err != nil { ctx.JSON(400, gin.H{"message": "提交ID无效"}); return }
	recordID, result, err := handler.Judge.Submit(ctx, id)
	if err != nil { ctx.JSON(500, gin.H{"message": err.Error()}); return }
	ctx.JSON(http.StatusOK, gin.H{"judgeRecordId": recordID, "result": result})
}
