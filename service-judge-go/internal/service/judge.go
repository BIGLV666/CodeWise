package service

import (
	"context"
	"fmt"

	"codewise-judge/internal/executor"
	"codewise-judge/internal/model"
	"codewise-judge/internal/repository"
)

type Judge struct {
	Repo *repository.MySQL
	Java executor.Java
}

func (service *Judge) ExecuteOne(ctx context.Context, request model.ExecuteRequest) model.JudgeResult {
	if request.Language != "java" { return model.JudgeResult{Status: "SYSTEM_ERROR", ErrorMessage: "当前只支持 Java"} }
	if request.TimeLimit <= 0 { request.TimeLimit = 2000 }
	return service.Java.Execute(ctx, request.Code, model.TestCase{Input: request.Input, Expected: request.Expected, TimeLimit: request.TimeLimit}, request.TimeLimit)
}

func (service *Judge) Submit(ctx context.Context, submitID int64) (int64, model.JudgeResult, error) {
	submit, err := service.Repo.FindSubmit(ctx, submitID)
	if err != nil { return 0, model.JudgeResult{}, err }
	if submit.QuestionType != "ACM" { return 0, model.JudgeResult{Status: "SYSTEM_ERROR", ErrorMessage: "函数模式尚未迁移"}, nil }
	cases, err := service.Repo.FindTestCases(ctx, submit.QuestionID)
	if err != nil { return 0, model.JudgeResult{}, err }
	if len(cases) == 0 { return 0, model.JudgeResult{Status: "SYSTEM_ERROR", ErrorMessage: "测试用例为空"}, nil }

	results := service.Java.ExecuteBatch(ctx, submit.Code, cases, submit.TimeLimit)
	final := results[len(results)-1]
	if final.Status == "AC" { final.FailIndex = 0 }
	recordID, err := service.Repo.SaveResult(ctx, submitID, submit.Code, final)
	if err != nil { return 0, final, fmt.Errorf("保存判题结果失败: %w", err) }
	return recordID, final, nil
}
