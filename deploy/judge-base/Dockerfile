# codewise-java-judge:17 —— ACM/函数题 Java 判题基础镜像
# 用法：docker build -t codewise-java-judge:17 deploy/judge-base/
# 要求与代码约定一致：
#   - JDK 17（javac/java）
#   - /opt/judge/lib 目录存在（函数模式编译 classpath 通配符需要，当前无第三方 jar，目录留空）
#   - uid/gid 1000 的 judge 用户（判题容器以 1000:1000 非 root 运行）
#   - /workspace 工作目录（运行时由 service-judge 挂载/覆盖，只读根 + tmpfs）
FROM eclipse-temurin:17-jdk

# 新版基础镜像（Ubuntu 24.04 系）自带 uid 1000 的 ubuntu 用户，先删除再建 judge，
# 保证判题容器以 1000:1000 非 root 运行（与代码约定一致）
RUN (getent passwd 1000 >/dev/null && userdel -r "$(id -nu 1000)") || true \
 && useradd -r -u 1000 -m judge \
 && mkdir -p /opt/judge/lib /workspace \
 && chown -R judge:judge /opt/judge /workspace

# 函数模式生成的判题代码 import jackson（反序列化 JSON 测试输入），
# 编译 classpath 为 /opt/judge/lib/*，这里随镜像内置固定版本三件套
# （与 CodeWise Spring Boot 3.2.4 BOM 的 jackson 2.15.x 对齐）
ARG JACKSON_VERSION=2.15.4
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && for a in jackson-databind jackson-core jackson-annotations; do \
        curl -fsSL "https://repo1.maven.org/maven2/com/fasterxml/jackson/core/$a/${JACKSON_VERSION}/$a-${JACKSON_VERSION}.jar" \
          -o "/opt/judge/lib/$a-${JACKSON_VERSION}.jar"; \
    done \
 && ls -la /opt/judge/lib

USER judge
WORKDIR /workspace
