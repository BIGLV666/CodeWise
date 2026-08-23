package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 内部 Java 函数产物验收器：AI 生成的 Generator/Main 源码一律在 Docker 沙箱内
 * 编译并执行，不允许宿主机执行（repair-plan §3 判题沙箱约束）。
 *
 * <p>沙箱保证：容器无网络（--network none）、内存 256m、CPU 1.0、进程数 128、
 * 只读根文件系统、/tmp 为 noexec tmpfs；工作目录以只读之外挂载进容器，
 * 编译产物与输入输出文件全部落回挂载目录。宿主机本地执行路径已移除，
 * Docker 不可用时验收直接失败（不静默降级）。</p>
 */
@Component
public class InternalJavaArtifactJudge {
    private static final int REQUIRED_CASE_COUNT = 50;

    private final ObjectMapper objectMapper;
    private final String dockerImage;
    private final String dockerCommand;
    private final int timeoutSeconds;

    public InternalJavaArtifactJudge(
            ObjectMapper objectMapper,
            @Value("${codewise.function-artifact.docker-image:codewise-java-judge:17}") String dockerImage,
            @Value("${codewise.function-artifact.docker-command:docker}") String dockerCommand,
            @Value("${codewise.function-artifact.timeout-seconds:30}") int timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.dockerImage = dockerImage;
        this.dockerCommand = dockerCommand;
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<ValidatedCase> validate(
            Path artifactDirectory,
            Path generatorSource,
            Path standardAnswerSource,
            long seed
    ) throws IOException, InterruptedException {
        Path judgeWorkspace = artifactDirectory.resolve(".judge-work");
        prepareJudgeWorkspace(artifactDirectory, judgeWorkspace, generatorSource, standardAnswerSource);
        Files.writeString(artifactDirectory.resolve("validate.sh"), buildScript(seed), StandardCharsets.UTF_8);

        try {
            validateInDocker(artifactDirectory);
            return readValidatedCases(artifactDirectory);
        } finally {
            cleanupJudgeWorkspace(judgeWorkspace);
        }
    }

    /** 准备判题工作区：源码规范化命名、清理上次产物（包可见便于离线单测）。 */
    void prepareJudgeWorkspace(
            Path artifactDirectory,
            Path judgeWorkspace,
            Path generatorSource,
            Path standardAnswerSource
    ) throws IOException {
        deleteJudgeWorkspace(judgeWorkspace);
        Files.createDirectories(judgeWorkspace);
        Files.copy(generatorSource, judgeWorkspace.resolve("Generator.java"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(standardAnswerSource, judgeWorkspace.resolve("Main.java"), StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(artifactDirectory.resolve("inputs.jsonl"));
        Files.deleteIfExists(artifactDirectory.resolve("outputs.jsonl"));
        Files.deleteIfExists(artifactDirectory.resolve("judge.log"));
    }

    private void validateInDocker(Path artifactDirectory) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(
                    dockerCommand, "run", "--rm",
                    "--network", "none",
                    "--memory", "256m",
                    "--cpus", "1.0",
                    "--pids-limit", "128",
                    "--read-only",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                    "-v", artifactDirectory.toAbsolutePath() + ":/workspace",
                    dockerImage,
                    "sh", "/workspace/validate.sh"
            ).redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "找不到 Docker 命令，请安装 Docker Desktop 或配置 codewise.function-artifact.docker-command",
                    exception
            );
        }

        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("内部 Java 判题超时");
        }
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Files.writeString(artifactDirectory.resolve("judge.log"), log, StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("内部 Java 判题失败，详情见 judge.log");
        }
    }

    /** 读取并校验生成器/标准答案产物（包可见便于离线单测）。 */
    List<ValidatedCase> readValidatedCases(Path artifactDirectory) throws IOException {
        List<String> inputs = Files.readAllLines(artifactDirectory.resolve("inputs.jsonl"), StandardCharsets.UTF_8);
        List<String> outputs = Files.readAllLines(artifactDirectory.resolve("outputs.jsonl"), StandardCharsets.UTF_8);
        if (inputs.size() != REQUIRED_CASE_COUNT || outputs.size() != REQUIRED_CASE_COUNT) {
            throw new IllegalStateException("生成器和标准答案必须各产生恰好 50 行数据");
        }

        List<ValidatedCase> cases = new ArrayList<>(REQUIRED_CASE_COUNT);
        for (int index = 0; index < REQUIRED_CASE_COUNT; index++) {
            JsonNode input = objectMapper.readTree(inputs.get(index));
            JsonNode output = objectMapper.readTree(outputs.get(index));
            if (!input.isArray()) {
                throw new IllegalStateException("第 " + (index + 1) + " 组输入不是 JSON 数组");
            }
            cases.add(new ValidatedCase(input, output));
        }
        return cases;
    }

    /** 构建容器内执行的验收脚本（包可见便于离线单测）。 */
    String buildScript(long seed) {
        return """
                #!/bin/sh
                set -eu
                cd /workspace/.judge-work
                javac -cp "/opt/judge/lib/*" -encoding UTF-8 Generator.java Main.java
                java -cp "/workspace/.judge-work:/opt/judge/lib/*" Generator %d 50 > /workspace/inputs.jsonl
                java -cp "/workspace/.judge-work:/opt/judge/lib/*" Main < /workspace/inputs.jsonl > /workspace/outputs.jsonl
                test "$(wc -l < /workspace/inputs.jsonl)" -eq 50
                test "$(wc -l < /workspace/outputs.jsonl)" -eq 50
                """.formatted(seed);
    }

    private void cleanupJudgeWorkspace(Path judgeWorkspace) {
        try {
            deleteJudgeWorkspace(judgeWorkspace);
        } catch (IOException ignored) {
        }
    }

    private void deleteJudgeWorkspace(Path judgeWorkspace) throws IOException {
        if (!Files.exists(judgeWorkspace)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(judgeWorkspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record ValidatedCase(JsonNode input, JsonNode output) {
    }
}
