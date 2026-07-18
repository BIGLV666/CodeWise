# CodeWise 项目体量快照

统计日期：2026-07-18。统计排除了 `.git`、`target`、`node_modules`、`dist` 和 IDE 目录，包含 Java、Vue/JS/TS、SQL、YAML 与 XML 等主要代码和配置文件。

## 总体规模

| 指标 | 数量 |
| --- | ---: |
| 微服务/公共模块 | 10 |
| 主要代码与配置文件 | 295 |
| 主要代码与配置行数 | 16,913 |

## 模块规模

| 模块 | 文件数 | 行数 |
| --- | ---: | ---: |
| `service-ai` | 46 | 3,491 |
| `service-question` | 52 | 3,262 |
| `service-community` | 47 | 2,793 |
| `service-review` | 33 | 2,240 |
| `service-judge` | 22 | 1,389 |
| `service-message` | 23 | 1,123 |
| `service-user` | 22 | 1,078 |
| `service-common` | 22 | 728 |
| `service-api` | 21 | 370 |
| `service-gateway` | 6 | 333 |

这是一份工程体量快照，不等同于 Git 托管平台的语言统计。生成代码、空行、注释和配置文件都会影响行数；后续迭代应以同一统计口径比较趋势。
