package executor

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"codewise-judge/internal/model"
)

type Java struct{}

// Execute 保留单测试点调用，调试接口可以直接复用。
func (Java) Execute(ctx context.Context, code string, test model.TestCase, defaultLimit int) model.JudgeResult {
	results := (Java{}).ExecuteBatch(ctx, code, []model.TestCase{test}, defaultLimit)
	if len(results) == 0 {
		return model.JudgeResult{Status: "SYSTEM_ERROR", ErrorMessage: "执行器没有返回结果"}
	}
	return results[0]
}

// ExecuteBatch 只编译一次，然后依次执行所有测试点。
func (Java) ExecuteBatch(ctx context.Context, code string, tests []model.TestCase, defaultLimit int) []model.JudgeResult {
	if len(tests) == 0 {
		return []model.JudgeResult{{Status: "SYSTEM_ERROR", ErrorMessage: "测试用例为空"}}
	}
	workspace, err := os.MkdirTemp("", "codewise-judge-*")
	if err != nil {
		return []model.JudgeResult{systemError(err)}
	}
	defer os.RemoveAll(workspace)

	if err := os.WriteFile(filepath.Join(workspace, "Main.java"), []byte(code), 0644); err != nil {
		return []model.JudgeResult{systemError(err)}
	}

	compile := exec.CommandContext(ctx, "javac", "-encoding", "UTF-8", "Main.java")
	compile.Dir = workspace
	compileOutput, err := compile.CombinedOutput()
	if err != nil {
		return compileError(len(tests), string(compileOutput))
	}

	results := make([]model.JudgeResult, 0, len(tests))
	for index, test := range tests {
		result := executeCompiled(ctx, workspace, test, defaultLimit)
		result.TestCaseID = test.ID
		result.FailIndex = index + 1
		result.TestTotal = len(tests)
		results = append(results, result)
		if result.Status != "AC" {
			break
		}
	}
	if len(results) > 0 && results[len(results)-1].Status == "AC" {
		results[len(results)-1].FailIndex = 0
	}
	return results
}

func executeCompiled(parent context.Context, workspace string, test model.TestCase, defaultLimit int) model.JudgeResult {
	start := time.Now()
	limit := test.TimeLimit
	if limit <= 0 { limit = defaultLimit }
	runCtx, cancel := context.WithTimeout(parent, time.Duration(limit)*time.Millisecond)
	defer cancel()

	run := exec.CommandContext(runCtx, "java", "Main")
	run.Dir = workspace
	run.Stdin = strings.NewReader(test.Input)
	var stdout, stderr bytes.Buffer
	run.Stdout, run.Stderr = &stdout, &stderr
	err := run.Run()
	used := elapsed(start)
	if runCtx.Err() == context.DeadlineExceeded {
		return model.JudgeResult{Status: "TLE", ErrorMessage: "超出时间限制", Log: stderr.String(), TimeUsed: used}
	}
	if err != nil {
		return model.JudgeResult{Status: "RE", ErrorMessage: "运行时错误", Log: stderr.String(), UserOutput: stdout.String(), TimeUsed: used}
	}

	actual := normalize(stdout.String())
	expected := normalize(test.Expected)
	result := model.JudgeResult{
		Status: "WA", Expected: expected, UserOutput: actual, Input: test.Input,
		TimeUsed: used, Log: stderr.String(),
	}
	if actual == expected {
		result.Status, result.ErrorMessage = "AC", "执行成功"
	} else {
		result.ErrorMessage = "答案错误"
	}
	return result
}

func compileError(total int, log string) []model.JudgeResult {
	return []model.JudgeResult{{Status: "CE", ErrorMessage: "编译失败", Log: log, TestTotal: total, FailIndex: 1}}
}

func normalize(value string) string {
	return strings.TrimRight(strings.ReplaceAll(value, "\r\n", "\n"), " \t\n")
}

func elapsed(start time.Time) int { return int(time.Since(start).Milliseconds()) }

func systemError(err error) model.JudgeResult {
	return model.JudgeResult{Status: "SYSTEM_ERROR", ErrorMessage: fmt.Sprintf("执行器错误: %v", err)}
}
