# 本地题库批量导入（tools/import-leetcode）

把本地刷题仓库（默认 `~/project/leetcode`）里**已通过自带对拍验证**的 Java 单方法题导入 CodeWise。

工具只做三件事：**扫仓库 → 本地对拍闸门 → 调服务端批量接口**；题面与测试数据的生成都在服务端完成，
不依赖任何第三方题面文本。

## 前置条件

- 本机 JDK（`javac`/`java` 在 PATH，用于本地对拍闸门）。
- 能 SSH 到目标服务器（默认走 `ssh <target>` 在服务器本机用 curl 打 `service-question:8084`）；
  也可以用 `--direct-url` 直连（需自己提供内部 Token 与管理员 ID）。
- 服务端已部署含 `/api/question/function/batch-import` 的版本，且：
  - `service-question` 能访问 MySQL/Redis/RabbitMQ；
  - `service-judge` 与判题镜像（`codewise-java-judge:17`）正常，隐藏用例靠它产出；
  - 需要 AI 重写题面时，`service-ai` 的指定端点（默认 `opencode-go`）配好 key。

## 用法

```bash
# 1. 扫描仓库，生成清单（会标出参数类型不受支持的题）
python3 codewise_import.py scan --repo ~/project/leetcode --out manifest.json

# 2. 本地闸门：跑题自带 Test.java（官方样例 + 参考实现对拍 + 随机压测），
#    并按判题镜像的 Java 版本（默认 17）再编译一遍解法
python3 codewise_import.py check --manifest manifest.json --out gate-report.json

# 3. 先试跑 5 道题
python3 codewise_import.py import \
  --manifest manifest.json --gate-report gate-report.json \
  --ssh root@<服务器> --limit 5 --batch-size 5 \
  --case-count 20 --report report.json

# 4. 放大：整批导入（失败题记录在报告里，可 --resume 重跑）
python3 codewise_import.py import \
  --manifest manifest.json --gate-report gate-report.json \
  --ssh root@<服务器> --batch-size 5 --resume --report report.json
```

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--limit N` | 只导入前 N 道（按目录层级排序，便于先试水） |
| `--only a,b` | 只导入指定 slug（slug = 目录名，Hot100 下用 `Hot100-xxx`） |
| `--batch-size N` | 每个批量任务包含多少道题（默认 5，建议 5~10） |
| `--case-count N` | 每题的隐藏用例数量（默认 20，服务端上限 100） |
| `--seed N` | 随机输入种子，便于复现同一批用例 |
| `--no-ai-description` | 不调用 AI 重写题面（需配合下面的题面来源） |
| `--descriptions FILE` | 题面 JSON：`{"slug 或标题": "题面正文"}`，命中则不调 AI |
| `--description-template "…"` | 兜底题面模板，支持 `{title}` / `{method}` 占位 |
| `--sample-count N` | 用例生成后回填为公开样例的条数（默认 1） |
| `--java-release 17` | 本地闸门按判题镜像的 Java 版本校验解法 |
| `--dry-run` | 只打印将要提交的内容，不发请求 |
| `--resume` | 跳过报告里已成功的题 |

## 本地闸门的两个检查

1. **对拍**：编译并运行题目自带的 `Test.java`（官方样例 + 参考实现对拍 + 随机压测），跑通才放行；
2. **判题版本编译**：判题容器是 **JDK 17**（`deploy/judge-base/Dockerfile`），而本地仓库常用
   JDK 21 的 API（`List.removeLast` / `getLast` 等）。这类解法本地能跑、进沙箱却编译失败，
   最终只会表现为「执行结果数量不完整」，所以在导入前用 `javac --release 17` 编一遍，
   不通过的直接挡在闸门外（报告里会写明原因）。

## 导入管线里服务端做了什么

1. 逐条建函数题（FUNCTION）：题面缺省时由 `service-ai` 依据**你的解法源码 + 参考实现 + 笔记**原创重写；
2. 把该题解法当作标准答案，按参数配置生成随机输入，送判题沙箱执行；
3. 判题回执把每个输入的真实输出写成隐藏用例；
4. 把最早的一条隐藏用例提升为公开样例并回填题目的样例字段（`--sample-count`），
   同时把题目置为上架；批量导入建题时是**私密**状态，所以用例生成失败的题不会以
   0 个测试点的形态暴露给用户。

题面与样例分两处生成，但样例来自真实执行结果，所以页面上看到的样例与判题数据必然一致。

## 目录结构

```text
tools/import-leetcode/
├── codewise_import.py      # 命令行入口（scan / check / import / status）
└── importer/
    ├── repo.py             # 仓库扫描：签名解析、题名/难度提取、解法清洗
    ├── gate.py             # 本地对拍闸门：javac + java 跑题目自带的 Test
    └── client.py           # 传输层：SSH + 服务端本地 curl，或 --direct-url 直连
```

## 已知限制

- **用例生成失败不会自动回滚**：题目会留在私密状态（`status=3`）、`0` 条用例，报告里标记失败。
  重导前需要管理员删除这些私密题（或沿用同一份题面继续生成）。
- 参数取值域（数值范围、长度、字符集）由 AI 依据它写的题面给出；模型给错约束时可在条目里显式传
  `parameterRanges`（含 `allowedChars`）覆盖。
- 依赖「题目级业务约束」的题（如「保证有解」「输入是合法括号串」）无法用区间表达，
  随机数据可能语义无效，需人工复核或后续补题目级生成规则。
- 仓库里没有 `Test.java` 的题会以「跳过对拍闸门」放行（报告 `skipped=true`）。

## 注意

- **不会上传仓库里的第三方题面**：只有解法代码、你自己的测试代码与笔记会进入题面重写提示词。
- 题面重写结果建议抽检：题意是否与解法一致、约束是否与随机数据规模一致。
- 内部 Token 与数据库口令只在内存中使用，不写入报告；`--direct-url` 模式下需自行传入。
- 没有 `Test.java` 的题会以「跳过闸门」放行（报告里 `skipped=true`），是否导入自行决定。
