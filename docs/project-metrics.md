# CodeWise 项目体量快照

统计日期：2026-08-29。统计排除了 `.git`、`target`、`node_modules`、`dist`、IDE 目录和运行时产物目录（如 `data/function-artifacts`），包含 Java、SQL、YAML 与 XML 等主要代码和配置文件（`src/` 下源码资源 + 各级 `pom.xml`）。

## 总体规模

| 指标 | 数量 |
| --- | ---: |
| 微服务/公共模块 | 10 |
| 主要代码与配置文件 | 464 |
| 主要代码与配置行数 | 40,617 |

## 模块规模

| 模块 | 文件数 | 行数 |
| --- | ---: | ---: |
| `service-question` | 86 | 7,727 |
| `service-ai` | 79 | 7,786 |
| `service-community` | 65 | 5,378 |
| `service-judge` | 53 | 6,300 |
| `service-review` | 41 | 3,833 |
| `service-message` | 36 | 3,143 |
| `service-common` | 37 | 2,184 |
| `service-user` | 23 | 1,580 |
| `service-api` | 27 | 707 |
| `service-gateway` | 6 | 659 |
| 各级 `pom.xml` | 11 | 1,320 |

这是一份工程体量快照，不等同于 Git 托管平台的语言统计。生成代码、空行、注释和配置文件都会影响行数；后续迭代应以同一统计口径比较趋势。
