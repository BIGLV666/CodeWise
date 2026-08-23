package executor

import (
	"context"
	"fmt"

	"codewise-judge/internal/model"
)

// Java 执行器桩：不可信代码不允许在宿主机执行（沙箱原则见
// docs/maintenance/repair-plan.md §三/§四），Docker 容器执行尚未实现。
//
// <p>现状：Execute/ExecuteBatch 一律返回 SYSTEM_ERROR，模块仅保留 HTTP/MQ
// 骨架供后续演进。容器化实现约定：经 Docker SDK（HTTP API，而非 docker CLI
// 子进程）创建容器——network none、内存/CPU/进程数限制、只读根文件系统、
// noexec tmpfs，用户代码与测试输入只经挂载文件与 stdin 流进入容器，
// 单用例时限由容器内 timeout 强制。</p>
type Java struct{}

// Execute 保留单测试点调用，调试接口可以直接复用。
func (Java) Execute(ctx context.Context, code string, test model.TestCase, defaultLimit int) model.JudgeResult {
	return unavailable()
}

// ExecuteBatch 只编译一次，然后依次执行所有测试点。
func (Java) ExecuteBatch(ctx context.Context, code string, tests []model.TestCase, defaultLimit int) []model.JudgeResult {
	return []model.JudgeResult{unavailable()}
}

// unavailable 返回统一的未容器化提示，避免调用方误判为代码问题。
func unavailable() model.JudgeResult {
	return model.JudgeResult{
		Status:       "SYSTEM_ERROR",
		ErrorMessage: fmt.Sprintf("Go 判题执行器尚未容器化（宿主机执行已按沙箱原则移除），不可信代码必须在容器内执行"),
	}
}
