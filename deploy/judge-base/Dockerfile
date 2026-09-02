# codewise-java-judge:17 —— ACM/函数题 Java 判题基础镜像
# 用法：docker build -t codewise-java-judge:17 deploy/judge-base/
# 要求与代码约定一致：
#   - JDK 17（javac/java）
#   - /opt/judge/lib 目录存在（函数模式编译 classpath 通配符需要，当前无第三方 jar，目录留空）
#   - uid/gid 1000 的 judge 用户（判题容器以 1000:1000 非 root 运行）
#   - /workspace 工作目录（运行时由 service-judge 挂载/覆盖，只读根 + tmpfs）
FROM eclipse-temurin:17-jdk

RUN useradd -r -u 1000 -m judge \
 && mkdir -p /opt/judge/lib /workspace \
 && chown -R judge:judge /opt/judge /workspace

USER judge
WORKDIR /workspace
