# CodeWise 项目体量快照

统计日期：2026-09-03。覆盖后端 Java 服务、Go 判题原型、Python Agent（含 dsh 插件）与前端仓库（兄弟目录 `CodeWise-frontend/CodeWise-frontend`，经 `deploy/frontend/Dockerfile` 一并构建部署）。

统计口径：排除 `.git`、`target`、`node_modules`、`dist`、`.venv`、`__pycache__`、IDE 目录和运行时产物目录（如 `data/function-artifacts`）；行数为文件原始行数，含空行与注释。

- 后端 Java：各服务 `src/` 下 Java/XML/YAML/SQL/properties，加上各级 `pom.xml`。
- Go 判题：`service-judge-go` 下 `.go` 文件。
- Agent：`codewise-agent` 下 `.py` 与插件 `.ts`。
- 前端：`src/` 下 `.vue/.ts/.js/.css/.scss`。

## 总体规模

| 部分 | 文件数 | 行数 |
| --- | ---: | ---: |
| 后端 Java（10 个模块 + pom） | 548 | 47,806 |
| Go 判题原型（service-judge-go） | 9 | 369 |
| Python Agent（codewise-agent） | 57 | 6,558 |
| 前端（CodeWise-frontend/src） | 77 | 28,285 |
| **合计** | **691** | **83,018** |

## 后端模块规模

| 模块 | 文件数 | 行数 |
| --- | ---: | ---: |
| `service-review` | 95 | 8,370 |
| `service-question` | 89 | 8,158 |
| `service-ai` | 79 | 7,804 |
| `service-community` | 77 | 6,374 |
| `service-judge` | 55 | 6,396 |
| `service-message` | 37 | 3,201 |
| `service-common` | 37 | 2,120 |
| `service-user` | 33 | 2,479 |
| `service-api` | 28 | 763 |
| `service-gateway` | 7 | 752 |
| 各级 `pom.xml` | 11 | 1,389 |

## Agent 与前端规模

| 部分 | 语言 | 文件数 | 行数 |
| --- | --- | ---: | ---: |
| `codewise-agent` | Python（FastAPI 门面、dsh 桥接、测试） | 43 | 3,654 |
| `codewise-agent` | TypeScript（codewise-tools / codewise-web 插件） | 14 | 2,904 |
| 前端 | Vue 3 + TypeScript + 样式 | 77 | 28,285 |

其中 Agent 工具插件 `codewise-tools` 注册 51 个网关工具（用户/题目/提交/复习/收藏夹/社区/学习计划/笔记），另有网页抓取与代码执行插件 `codewise-web`。

这是一份工程体量快照，不等同于 Git 托管平台的语言统计。生成代码、空行、注释和配置文件都会影响行数；后续迭代应以同一统计口径比较趋势。
