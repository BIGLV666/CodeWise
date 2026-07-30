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

@Component
public class InternalJavaArtifactJudge {
    private static final int REQUIRED_CASE_COUNT = 50;

    private final ObjectMapper objectMapper;
    private final String dockerImage;
    private final String dockerCommand;
    private final String judgeMode;
    private final int timeoutSeconds;

    public InternalJavaArtifactJudge(
            ObjectMapper objectMapper,
            @Value("${codewise.function-artifact.docker-image:codewise-java-judge:17}") String dockerImage,
            @Value("${codewise.function-artifact.docker-command:docker}") String dockerCommand,
            @Value("${codewise.function-artifact.judge-mode:local}") String judgeMode,
            @Value("${codewise.function-artifact.timeout-seconds:30}") int timeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.dockerImage = dockerImage;
        this.dockerCommand = dockerCommand;
        this.judgeMode = judgeMode;
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
            if ("local".equalsIgnoreCase(judgeMode)) {
                return validateLocally(artifactDirectory, judgeWorkspace, seed);
            }
            validateInDocker(artifactDirectory);
            return readValidatedCases(artifactDirectory);
        } finally {
            cleanupJudgeWorkspace(judgeWorkspace);
        }
    }

    private void prepareJudgeWorkspace(
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

    private List<ValidatedCase> validateLocally(
            Path artifactDirectory,
            Path judgeWorkspace,
            long seed
    ) throws IOException, InterruptedException {
        Path classesDirectory = judgeWorkspace.resolve("classes");
        Files.createDirectories(classesDirectory);
        Path logFile = artifactDirectory.resolve("judge.log");
        String compileClassPath = System.getProperty("java.class.path", "");
        String runtimeClassPath = classesDirectory + java.io.File.pathSeparator + compileClassPath;

        runLocal(
                List.of(
                        "javac", "-cp", compileClassPath,
                        "-encoding", "UTF-8",
                        "-d", classesDirectory.toString(),
                        "Generator.java", "Main.java"
                ),
                judgeWorkspace,
                logFile,
                "本地编译失败"
        );
        runLocal(
                List.of(
                        "java", "-cp", runtimeClassPath,
                        "Generator", String.valueOf(seed), String.valueOf(REQUIRED_CASE_COUNT)
                ),
                judgeWorkspace,
                null,
                artifactDirectory.resolve("inputs.jsonl"),
                logFile,
                "随机生成器执行失败"
        );
        runLocal(
                List.of("java", "-cp", runtimeClassPath, "Main"),
                judgeWorkspace,
                artifactDirectory.resolve("inputs.jsonl"),
                artifactDirectory.resolve("outputs.jsonl"),
                logFile,
                "标准答案执行失败"
        );

        return readValidatedCases(artifactDirectory);
    }

    private void runLocal(
            List<String> command,
            Path directory,
            Path logFile,
            String errorMessage
    ) throws IOException, InterruptedException {
        runLocal(command, directory, null, null, logFile, errorMessage);
    }

    private void runLocal(
            List<String> command,
            Path directory,
            Path inputFile,
            Path outputFile,
            Path logFile,
            String errorMessage
    ) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        if (inputFile != null) {
            builder.redirectInput(inputFile.toFile());
        }
        if (outputFile != null) {
            builder.redirectOutput(outputFile.toFile());
            builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        } else {
            builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new IllegalStateException("本地 Java 环境不可用，请确认 javac/java 已加入 PATH", exception);
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(errorMessage + "：执行超时");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(errorMessage + "，详情见 judge.log");
        }
    }

    private List<ValidatedCase> readValidatedCases(Path artifactDirectory) throws IOException {
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

    private String buildScript(long seed) {
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
