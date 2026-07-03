package org.example.servicejudge.judge;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.example.serviceapi.dto.JudgeResultDto;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.TestCase;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class JudgeService implements JudgeInterface {

    private static final int TIME_LIMIT_MS = 2000;
    private static final long MEMORY_LIMIT_BYTES = 256L * 1024 * 1024;

    @Autowired
    private DockerClient dockerClient;

    // 容器池：语言 -> 容器ID
    private final Map<String, String> containerPool = new ConcurrentHashMap<>();

    // ========== 初始化：预创建所有语言的容器 ==========
    @PostConstruct
    public void init() {
        log.info("开始初始化容器池...");
        for (LanguageSpec spec : LanguageSpec.values()) {
            try {
                String containerId = createContainer(spec);
                containerPool.put(spec.language, containerId);
                log.info("✅ 预创建 {} 容器: {}", spec.language, containerId);
            } catch (Exception e) {
                log.error("❌ 创建 {} 容器失败: {}", spec.language, e.getMessage());
            }
        }
        log.info("容器池初始化完成，共 {} 个容器", containerPool.size());
    }

    // ========== 销毁：清理所有容器 ==========
    @PreDestroy
    public void destroy() {
        log.info("开始清理容器池...");
        for (Map.Entry<String, String> entry : containerPool.entrySet()) {
            try {
                dockerClient.removeContainerCmd(entry.getValue()).withForce(true).exec();
                log.info("✅ 删除 {} 容器: {}", entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("删除 {} 容器失败: {}", entry.getKey(), e.getMessage());
            }
        }
        containerPool.clear();
    }

    // ========== 判题主方法 ==========
    @Override

    public JudgeRecord executeCode(String code, String language, TestCase testMessage) {
        String input = testMessage.getInputData();
        String output = testMessage.getExpectedOutput();
        Long testCaseId = testMessage.getCaseId();
        int failIndex = 0;  // 默认失败索引为 0

        if (code == null || code.isBlank()) {
            return buildResult("CE", output, null, 0, 0, "代码不能为空", failIndex, testCaseId, null, input);
        }

        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            return buildResult("CE", output, null, 0, 0, "不支持的语言: " + language, failIndex, testCaseId, null, input);
        }

        String containerId = containerPool.get(language);
        if (containerId == null) {
            return buildResult("RE", output, null, 0, 0, "沙箱环境未就绪，请稍后重试", failIndex, testCaseId, null, input);
        }

        long start = System.currentTimeMillis();

        try {
            cleanContainerWorkspace(containerId);
            Path workspace = Files.createTempDirectory("codewise-judge-");
            try {
                Files.writeString(workspace.resolve(spec.sourceFile()), code, StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("input.txt"), input == null ? "" : input, StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("run.sh"), spec.buildRunScript(), StandardCharsets.UTF_8);
                workspace.resolve("run.sh").toFile().setExecutable(true);

                copyWorkspaceToContainer(containerId, workspace);
                Integer exitCode = executeInContainer(containerId);
                int timeUsed = (int) (System.currentTimeMillis() - start);

                String stdout = readFileFromContainer(containerId, "/workspace/stdout.txt");
                String stderr = readFileFromContainer(containerId, "/workspace/stderr.txt");
                int memoryUsed = getContainerMemory(containerId);

                // 如果 exec 没有返回 124（TLE），我们再尝试从文件读取以确保准确捕获 Java/Python 内部抛出的异常退出码
                if (exitCode == null || exitCode != 124) {
                    String exitCodeText = readFileFromContainer(containerId, "/workspace/exitcode.txt").trim();
                    if (!exitCodeText.isEmpty()) {
                        try {
                            exitCode = Integer.parseInt(exitCodeText);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }

                // ============ 核心改造部分：友好的错误信息处理 ============

                if (exitCode != null && exitCode == 124) {
                    return buildResult("TLE", output, stdout, timeUsed, memoryUsed, "超出时间限制", failIndex, testCaseId, stderr, input);
                }

                if (exitCode != null && exitCode == 2) {
                    String cleanErr = formatCompileError(stderr);
                    return buildResult("CE", output, stdout, timeUsed, memoryUsed, cleanErr, failIndex, testCaseId, stderr, input);
                }

                if (exitCode != null && exitCode != 0) {
                    String cleanErr = formatRuntimeError(stderr, language);
                    return buildResult("RE", output, stdout, timeUsed, memoryUsed, cleanErr, failIndex, testCaseId, stderr, input);
                }

                String actual = normalizeOutput(stdout);
                String expected = normalizeOutput(output);

                if (actual.equals(expected)) {
                    return buildResult("AC", output, actual, timeUsed, memoryUsed, "执行成功", failIndex, testCaseId, stdout, input);
                }

                // WA 的情况，像力扣一样告诉用户输入、输出和期望
                String waErr = formatWrongAnswerError(input, expected, actual);
                return buildResult("WA", output, actual, timeUsed, memoryUsed, waErr, failIndex, testCaseId, stdout, input);

            } finally {
                Files.walk(workspace)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (Exception e) {
            log.error("判题系统内部错误", e);
            int timeUsed = (int) (System.currentTimeMillis() - start);
            return buildResult("RE", output, null, timeUsed, 0, "判题系统内部错误: " + e.getMessage(), failIndex, testCaseId, null, input);
        }
    }
    /**
     * 使用 Docker Java API 获取容器内存使用量 (KB)
     */
    /**
     * 获取容器内存使用量 (KB)
     */
    private int getContainerMemory(String containerId) {
        try {
            final int[] memoryKB = {0};
            final boolean[] received = {false};

            dockerClient.statsCmd(containerId)
                    .exec(new ResultCallback.Adapter<com.github.dockerjava.api.model.Statistics>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Statistics stats) {
                            if (stats != null && stats.getMemoryStats() != null) {
                                long usage = stats.getMemoryStats().getUsage();
                                memoryKB[0] = (int) (usage / 1024);
                                received[0] = true;
                            }
                        }
                    })
                    .awaitCompletion(2, TimeUnit.SECONDS);

            return memoryKB[0];
        } catch (Exception e) {
            log.warn("获取内存使用失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 解析内存字符串 "12.34MiB" -> 返回 KB
     */
    private int parseMemoryString(String memStr) {
        try {
            String[] parts = memStr.trim().split(" ");
            if (parts.length >= 2) {
                double value = Double.parseDouble(parts[0]);
                String unit = parts[1];
                if (unit.contains("GiB")) return (int) (value * 1024 * 1024);
                if (unit.contains("MiB")) return (int) (value * 1024);
                if (unit.contains("KiB")) return (int) value;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * 构建判题结果
     */
    private JudgeRecord buildResult(String status, String expected, String actual,
                                    int timeUsed, int memoryUsed, String error,
                                    int failIndex, Long testCaseId, String log, String input) {
        return JudgeRecord.builder()
                .submitStatus(status)
                .expectedOutput(expected)
                .userOutput(actual)
                .timeUsed(timeUsed)
                .memoryUsed(memoryUsed)
                .errorMsg(error)
                .testCaseId(testCaseId)
                .failIndex(failIndex)
                .log(log)
                .inputData(input)
                .build();
    }

    /**
     * 格式化编译错误 (CE)
     */
    private String formatCompileError(String stderr) {
        if (stderr == null || stderr.isEmpty()) return "编译失败，未知错误。";
        // 简单截取核心错误行（通常包含 error:）
        StringBuilder sb = new StringBuilder("编译错误:\n");
        for (String line : stderr.split("\n")) {
            // 过滤掉太长的文件路径，只保留关键信息
            if (line.contains("error:") || line.contains("错误:")) {
                sb.append(line.substring(line.indexOf(":") + 1).trim()).append("\n");
            }
        }
        if (sb.length() < 10) { // 如果没匹配到 error: 关键字，直接返回前500个字符避免太长
            return "编译错误:\n" + stderr.substring(0, Math.min(stderr.length(), 500));
        }
        return sb.toString().trim();
    }

    /**
     * 格式化运行时错误 (RE)
     */
    private String formatRuntimeError(String stderr, String language) {
        if (stderr == null || stderr.isEmpty()) return "程序运行时发生崩溃。";

        // 像 Java 通常是 Exception in thread "main" java.lang.XxxException
        // Python 通常是 Traceback... XxxError: ...
        for (String line : stderr.split("\n")) {
            line = line.trim();
            if (line.contains("Exception") || line.contains("Error:") || line.contains("Error:")) {
                return "运行时错误: " + line.substring(line.indexOf(":") + 1).trim();
            }
        }
        // 没找到具体的异常类，返回前200个字符
        return "运行时错误:\n" + stderr.substring(0, Math.min(stderr.length(), 200));
    }

    /**
     * 格式化答案错误 (WA)，类似力扣展示
     */
    private String formatWrongAnswerError(String input, String expected, String actual) {
        // 截断过长的输入和输出，防止前端卡死
        String truncatedInput = input != null ? input.substring(0, Math.min(input.length(), 300)) : "";
        String truncatedExpected = expected != null ? expected.substring(0, Math.min(expected.length(), 300)) : "";
        String truncatedActual = actual != null ? actual.substring(0, Math.min(actual.length(), 300)) : "";

        return String.format(
                "解答错误\n" +
                        "输入: %s\n" +
                        "输出: %s\n" +
                        "预期: %s",
                truncatedInput.isEmpty() ? "(空)" : truncatedInput,
                truncatedActual.isEmpty() ? "(空)" : truncatedActual,
                truncatedExpected.isEmpty() ? "(空)" : truncatedExpected
        );
    }


    // ========== 创建容器 ==========
    private String createContainer(LanguageSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(MEMORY_LIMIT_BYTES)
                .withMemorySwap(MEMORY_LIMIT_BYTES)
                .withNetworkMode("none");

        CreateContainerResponse container = dockerClient.createContainerCmd(spec.image)
                .withHostConfig(hostConfig)
                .withCmd("sh", "-c", "mkdir -p /workspace && while true; do sleep 3600; done")
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();

        // ★ 设置工作目录为 /workspace（通过 exec 修改，但容器重启会丢失）
        // 更好的方式是在复制文件时指定完整路径

        return container.getId();
    }

    // ========== 清理容器工作目录 ==========
    private void cleanContainerWorkspace(String containerId) {
        try {
            // 删除 /workspace 下的所有文件
            dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "-c", "rm -rf /workspace/*")
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
        } catch (Exception e) {
            log.warn("清理容器工作目录失败", e);
        }
    }

    // ========== 复制文件到容器 ==========

    /**
     * 复制工作目录到容器（手动打包）
     */
    private void copyWorkspaceToContainer(String containerId, Path workspace) throws IOException {
        // 用内存流打包
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(baos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            Files.walk(workspace)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            String entryName = workspace.relativize(file).toString();
                            TarArchiveEntry entry = new TarArchiveEntry(entryName);
                            entry.setSize(Files.size(file));
                            tos.putArchiveEntry(entry);
                            Files.copy(file, tos);
                            tos.closeArchiveEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            tos.finish();
        }

        // 从内存复制到容器
        try (ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray())) {
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withTarInputStream(bais)
                    .withRemotePath("/workspace/")  // ★ 改成 /workspace/
                    .exec();
        }
    }

    // ========== 在容器中执行命令 ==========
    private Integer executeInContainer(String containerId) {
        try {
            var execCmd = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "/workspace/run.sh")
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            // 等待执行完成，如果超时未完成返回 false
            boolean completed = dockerClient.execStartCmd(execCmd.getId())
                    .exec(new ResultCallback.Adapter<>() {
                    })
                    .awaitCompletion(TIME_LIMIT_MS + 1000, TimeUnit.MILLISECONDS); // 给1秒缓冲

            if (!completed) {
                // 如果发生超时，说明肯定是 TLE，直接返回 124
                log.warn("容器内执行超时，判定为 TLE");
                return 124;
            }

            // 正确的获取 exec 退出码的方法
            var execState = dockerClient.inspectExecCmd(execCmd.getId()).exec();
            Integer exitCode = execState.getExitCode();

            log.debug("容器执行完成，退出码: {}", exitCode);
            return exitCode != null ? exitCode : 1;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Exception e) {
            log.error("执行命令失败", e);
            return 1;
        }
    }

    // ========== 从容器读取文件 ==========
    private String readFileFromContainer(String containerId, String path) {
        try {
            var execCmd = dockerClient.execCreateCmd(containerId)
                    .withCmd("cat", path)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            dockerClient.execStartCmd(execCmd.getId())
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(com.github.dockerjava.api.model.Frame item) {
                            try {
                                out.write(item.getPayload());
                            } catch (IOException e) {
                                log.warn("读取输出失败", e);
                            }
                        }
                    })
                    .awaitCompletion(2, TimeUnit.SECONDS);

            return out.toString(StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.warn("读取容器文件失败: {}", path, e);
            return "";
        }
    }

    // ========== 辅助方法 ==========

    private String normalizeOutput(String text) {
        if (text == null) {
            return "";
        }
        return Arrays.stream(text.stripTrailing().split("\n", -1))
                .map(line -> line.stripTrailing())
                .collect(Collectors.joining("\n"));
    }



    // ========== 语言配置 ==========
    // ========== 语言配置 ==========
    private enum LanguageSpec {
        JAVA("java", "Main.java", "eclipse-temurin:17-jdk-alpine",
                """
                #!/bin/sh
                cd /workspace
                javac -encoding UTF-8 Main.java 2> stderr.txt
                if [ $? -ne 0 ]; then
                    echo 2 > exitcode.txt
                    exit 2
                fi
                timeout %ds java -Xmx256m Main < input.txt > stdout.txt 2>> stderr.txt
                echo $? > exitcode.txt
                """.formatted(TIME_LIMIT_MS / 1000)),

        PYTHON("python", "solution.py", "python:3.11-alpine",
                """
                #!/bin/sh
                cd /workspace
                timeout %ds python3 solution.py < input.txt > stdout.txt 2> stderr.txt
                echo $? > exitcode.txt
                """.formatted(TIME_LIMIT_MS / 1000)),

        CPP("cpp", "solution.cpp", "gcc:13",
                """
                #!/bin/sh
                cd /workspace
                g++ -O2 -std=c++17 solution.cpp -o solution 2> stderr.txt
                if [ $? -ne 0 ]; then
                    echo 2 > exitcode.txt
                    exit 2
                fi
                timeout %ds ./solution < input.txt > stdout.txt 2>> stderr.txt
                echo $? > exitcode.txt
                """.formatted(TIME_LIMIT_MS / 2000)),

        C("c", "solution.c", "gcc:13",
                """
                #!/bin/sh
                cd /workspace
                gcc -O2 solution.c -o solution -lm 2> stderr.txt
                if [ $? -ne 0 ]; then
                    echo 2 > exitcode.txt
                    exit 2
                fi
                timeout %ds ./solution < input.txt > stdout.txt 2>> stderr.txt
                echo $? > exitcode.txt
                """.formatted(TIME_LIMIT_MS / 2000));

        private final String language;
        private final String sourceFile;
        private final String image;
        private final String runScript;

        LanguageSpec(String language, String sourceFile, String image, String runScript) {
            this.language = language;
            this.sourceFile = sourceFile;
            this.image = image;
            this.runScript = runScript;
        }

        String sourceFile() {
            return sourceFile;
        }

        String buildRunScript() {
            return runScript;
        }

        static LanguageSpec of(String language) {
            if (language == null) {
                return null;
            }
            String key = language.trim().toLowerCase(Locale.ROOT);
            return Arrays.stream(values())
                    .filter(spec -> spec.language.equals(key))
                    .findFirst()
                    .orElse(null);
        }
    }







    /**
     * 打包目录为 tar
     */
    private void tarDirectory(Path source, Path target) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target.toFile());
             TarArchiveOutputStream tos = new TarArchiveOutputStream(fos)) {

            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            Files.walk(source)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            String entryName = source.relativize(file).toString();
                            TarArchiveEntry entry = new TarArchiveEntry(file.toFile(), entryName);
                            tos.putArchiveEntry(entry);
                            Files.copy(file, tos);
                            tos.closeArchiveEntry();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }
}