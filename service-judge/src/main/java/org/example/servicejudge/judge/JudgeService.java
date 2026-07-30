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
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.Util.BuildResult;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.vo.DockersStatusVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
public class JudgeService implements JudgeInterface {

    private static final int TIME_LIMIT_MS = 2000;
    private static final long COMPILE_TIMEOUT_MS = 15_000L;
    private static final long EXEC_OVERHEAD_MS = 2_000L;
    private static final long BATCH_STARTUP_TIMEOUT_MS = 7_000L;
    private static final long CONTAINER_BORROW_TIMEOUT_SECONDS = 10L;
    private static final long MEMORY_LIMIT_BYTES = 256L * 1024 * 1024;

    @Autowired
    private DockerClient dockerClient;
    @Autowired
    private UserFeignClient userFeignClient;


    private final Map<String, BlockingQueue<String>> containerPools = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> waitingCounts = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> allContainerIds = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> configuredContainerCounts = new ConcurrentHashMap<>();
    private final Map<String, Object> containerPoolLocks = new ConcurrentHashMap<>();
    private static final Map<LanguageSpec, Integer> INITIAL_CONTAINER_COUNTS = Map.of(
            LanguageSpec.JAVA, 2,
            LanguageSpec.PYTHON, 1,
            LanguageSpec.CPP, 1,
            LanguageSpec.C, 1
    );


    //容器状态区域
    public List<DockersStatusVo> getDockers() {
        checkAdmin();
        List<DockersStatusVo> result = new ArrayList<>();
        for (LanguageSpec spec : LanguageSpec.values()) {
            Set<String> allIds = new HashSet<>(allContainerIds.getOrDefault(spec.language, Set.of()));
            List<String> idleIds = containerPools.getOrDefault(spec.language, new LinkedBlockingQueue<>())
                    .stream()
                    .filter(allIds::contains)
                    .sorted()
                    .toList();
            Set<String> busyIdSet = new HashSet<>(allIds);
            idleIds.forEach(busyIdSet::remove);
            List<String> busyIds = busyIdSet.stream().sorted().toList();

            DockersStatusVo status = new DockersStatusVo();
            status.setLanguage(spec.language);
            status.setTotal(allIds.size());
            status.setIdle(idleIds.size());
            status.setBusy(busyIds.size());
            status.setWaiting(waitingCounts.getOrDefault(spec.language, new AtomicInteger()).get());
            status.setConfiguredCapacity(configuredContainerCounts.getOrDefault(spec.language, new AtomicInteger()).get());
            status.setIdleContainerIds(idleIds);
            status.setBusyContainerIds(busyIds);
            result.add(status);
        }
        return result;
    }

    //新增容器
    public void addDocker(String language) {
        checkAdmin();
        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            throw new IllegalArgumentException("不支持的语言: " + language);
        }

        String containerId = null;
        try {
            containerId = createContainer(spec);
            synchronized (containerPoolLocks.computeIfAbsent(spec.language, key -> new Object())) {
                BlockingQueue<String> idlePool = containerPools.computeIfAbsent(
                        spec.language,
                        key -> new LinkedBlockingQueue<>()
                );
                if (!idlePool.offer(containerId)) {
                    throw new IllegalStateException("容器空闲队列已满");
                }
                allContainerIds.computeIfAbsent(spec.language, key -> ConcurrentHashMap.newKeySet()).add(containerId);
                configuredContainerCounts.computeIfAbsent(spec.language, key -> new AtomicInteger()).incrementAndGet();
            }
            log.info("创建 {} 容器成功: {}", spec.language, containerId);
        } catch (Exception exception) {
            log.error("创建 {} 容器失败", spec.language, exception);
            if (containerId != null) {
                BlockingQueue<String> idlePool = containerPools.get(spec.language);
                if (idlePool != null) {
                    idlePool.remove(containerId);
                }
                Set<String> containerIds = allContainerIds.get(spec.language);
                if (containerIds != null) {
                    containerIds.remove(containerId);
                }
                removeContainerQuietly(containerId);
            }
            throw new IllegalStateException("创建 " + spec.language + " 容器失败", exception);
        }
    }
    //删除容器
    public boolean removeDocker(String language, String containerId) {
        checkAdmin();
        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            throw new IllegalArgumentException("不支持的语言: " + language);
        }
        if (containerId == null || containerId.isBlank()) {
            throw new IllegalArgumentException("容器 ID 不能为空");
        }

        BlockingQueue<String> idlePool = containerPools.get(spec.language);
        Set<String> allIds = allContainerIds.get(spec.language);
        if (idlePool == null || allIds == null || !allIds.contains(containerId)) {
            throw new IllegalArgumentException("该容器不存在");
        }

        synchronized (containerPoolLocks.computeIfAbsent(spec.language, key -> new Object())) {
            if (allIds.size() <= 1) {
                throw new IllegalStateException("每种语言至少保留一个容器");
            }
            if (!idlePool.remove(containerId)) {
                throw new IllegalStateException("容器正在使用，不能删除");
            }
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                allIds.remove(containerId);
                configuredContainerCounts.get(spec.language).decrementAndGet();
                log.info("删除 {} 容器成功: {}", spec.language, containerId);
                return true;
            } catch (Exception exception) {
                idlePool.offer(containerId);
                throw new IllegalStateException("删除容器失败: " + containerId, exception);
            }
        }
    }

    private void checkAdmin() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new IllegalArgumentException("用户未登录");
        }
        Result<UserDto> response = userFeignClient.getUserInfo(userId);
        if (response == null || response.getData() == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (!Objects.equals(response.getData().getRoleId(), 2)) {
            throw new IllegalArgumentException("无权管理判题容器");
        }
    }







    private String borrowContainer(String language) {
        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            return null;
        }
        BlockingQueue<String> pool = containerPools.get(spec.language);
        if (pool == null) {
            return null;
        }
        String containerId = pool.poll();
        if (containerId != null) {
            return containerId;
        }
        AtomicInteger waiting = waitingCounts.computeIfAbsent(
                spec.language,
                key -> new AtomicInteger()
        );
        try {
            waiting.incrementAndGet();
            return pool.poll(CONTAINER_BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("等待空闲 {} 容器时线程被中断", spec.language);
            return null;
        } finally {
            waiting.decrementAndGet();
        }
    }

    private void returnContainer(String language, String containerId) {
        if (containerId == null) {
            return;
        }
        LanguageSpec spec = LanguageSpec.of(language);
        BlockingQueue<String> pool = spec == null ? null : containerPools.get(spec.language);
        if (pool == null) {
            log.warn("容器归还失败，语言池不存在: language={}, containerId={}", language, containerId);
            return;
        }
        String availableContainerId = containerId;
        if (!cleanContainerWorkspace(containerId)) {
            availableContainerId = replaceContainer(spec, containerId);
        }
        if (availableContainerId != null && !pool.offer(availableContainerId)) {
            log.warn("容器归还失败，容器池已满: language={}, containerId={}", spec.language, containerId);
        }
    }

    private String replaceContainer(LanguageSpec spec, String containerId) {
        Set<String> containerIds = allContainerIds.computeIfAbsent(
                spec.language,
                key -> ConcurrentHashMap.newKeySet()
        );
        containerIds.remove(containerId);
        removeContainerQuietly(containerId);
        try {
            String replacementId = createContainer(spec);
            containerIds.add(replacementId);
            log.info("已重建 {} 容器: old={}, new={}", spec.language, containerId, replacementId);
            return replacementId;
        } catch (Exception exception) {
            log.error("重建 {} 容器失败，容器池容量暂时减少", spec.language, exception);
            return null;
        }
    }


    // ========== 初始化：预创建所有语言的容器 ==========
    @PostConstruct
    public void init() {
        log.info("开始初始化容器池...");
        for (LanguageSpec spec : LanguageSpec.values()) {
            int containerCount = INITIAL_CONTAINER_COUNTS.getOrDefault(spec, 1);
            BlockingQueue<String> pool = new LinkedBlockingQueue<>();
            Set<String> containerIds = ConcurrentHashMap.newKeySet();
            containerPools.put(spec.language, pool);
            allContainerIds.put(spec.language, containerIds);
            waitingCounts.put(spec.language, new AtomicInteger());
            configuredContainerCounts.put(spec.language, new AtomicInteger(containerCount));
            containerPoolLocks.put(spec.language, new Object());
            for (int index = 0; index < containerCount; index++) {
                try {
                    String containerId = createContainer(spec);
                    containerIds.add(containerId);
                    pool.offer(containerId);
                    log.info("预创建 {} 容器成功: {}", spec.language, containerId);
                } catch (Exception exception) {
                    log.error("创建 {} 容器失败", spec.language, exception);
                }
            }
        }
        int total = allContainerIds.values().stream().mapToInt(Set::size).sum();
        log.info("容器池初始化完成，共 {} 个容器", total);
    }

    // ========== 销毁：清理所有容器 ==========
    @PreDestroy
    public void destroy() {
        log.info("开始清理容器池...");
        for (Set<String> containerIds : allContainerIds.values()) {
            for (String containerId : containerIds) {
                removeContainerQuietly(containerId);
            }
        }
        containerPools.clear();
        allContainerIds.clear();
        waitingCounts.clear();
        configuredContainerCounts.clear();
        containerPoolLocks.clear();
    }
    // ========== 判题主方法 ==========
    @Override

    public JudgeReturnDto executeCode(String code, String language, String input) {


        // 默认失败索引为 0

        if (code == null || code.isBlank()) {
            return formatJudgeReturnDto(null, 0, null, "代码不能为空", null, 0);
        }

        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            return formatJudgeReturnDto(null, 0, null, "不支持的语言", null, 0);
        }

        String containerId = borrowContainer(language);

        if (containerId == null) {
            return formatJudgeReturnDto(null, 0, null, "业务繁忙请稍后重试", null, 0);

        }
        long start = System.currentTimeMillis();

        try {
            cleanContainerWorkspace(containerId);
            Path workspace = Files.createTempDirectory("codewise-judge-");
            try {
                Files.writeString(workspace.resolve(spec.sourceFile()), code, StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("input.txt"), input == null ? "" : input, StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("compile.sh"), spec.buildCompileScript(), StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("run.sh"), spec.buildRunScript(), StandardCharsets.UTF_8);
                workspace.resolve("compile.sh").toFile().setExecutable(true);
                workspace.resolve("run.sh").toFile().setExecutable(true);

                copyWorkspaceToContainer(containerId, workspace);
                Integer compileExitCode = executeScriptInContainer(containerId, "/workspace/compile.sh", COMPILE_TIMEOUT_MS);
                if (compileExitCode == null || compileExitCode != 0) {
                    int timeUsed = (int) (System.currentTimeMillis() - start);
                    String stderr = readFileFromContainer(containerId, "/workspace/stderr.txt");
                    return formatJudgeReturnDto(2, timeUsed, stderr, "编译失败", null, 0);
                }

                Integer exitCode = executeScriptInContainer(containerId, "/workspace/run.sh");
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
                String cleanErr = formatRuntimeError(stderr, language);
                JudgeReturnDto judgeReturnDto = new JudgeReturnDto();
                judgeReturnDto.setExitCode(exitCode);
                judgeReturnDto.setStdout(stdout);
                judgeReturnDto.setLog(stderr);
                judgeReturnDto.setMemoryUsed(memoryUsed);
                judgeReturnDto.setTimeUsed(timeUsed);
                judgeReturnDto.setErrorMsg(cleanErr);
                return judgeReturnDto;
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
            return formatJudgeReturnDto(null, null, "判题系统内部错误:" + e.getMessage(), "判题系统内部错误", null, null);
        } finally {
            returnContainer(language, containerId);
        }
    }


    @Override
    public JudgeRecord batchExecuteCode(String code, String language, List<TestDto> testCases) throws IOException {
        if (testCases == null || testCases.isEmpty()) {
            return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0),null,null,null,0,language);
        }
        if (code == null || code.isBlank()) {
            return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "代码不能为空", null, 0),testCases.get(0).getCaseId(),testCases.get(0).getInputData(),testCases.get(0).getExpectedOutput(),1,language);
        }

        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "不支持的语言", null, 0),testCases.get(0).getCaseId(),testCases.get(0).getInputData(),testCases.get(0).getExpectedOutput(),1,language);

        }

        String containerId = borrowContainer(language);
        if (containerId == null) {
           return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "业务繁忙，请稍后重试", null, 0),testCases.get(0).getCaseId(),testCases.get(0).getInputData(),testCases.get(0).getExpectedOutput(),1,language);
        }

        Path workspace = null;
        try {
            cleanContainerWorkspace(containerId);
            workspace = Files.createTempDirectory("codewise-judge-");
            Files.writeString(workspace.resolve(spec.sourceFile()), code, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("compile.sh"),spec.buildCompileScript(), StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("run.sh"), spec.buildRunScript(), StandardCharsets.UTF_8);
            workspace.resolve("compile.sh").toFile().setExecutable(true);
            workspace.resolve("run.sh").toFile().setExecutable(true);

            copyWorkspaceToContainer(containerId, workspace);

            long compileStart = System.currentTimeMillis();
            Integer compileExitCode = executeScriptInContainer(containerId, "/workspace/compile.sh", COMPILE_TIMEOUT_MS);
            if (compileExitCode == null || compileExitCode != 0) {
                int timeUsed = (int) (System.currentTimeMillis() - compileStart);
                String stderr = readFileFromContainer(containerId, "/workspace/stderr.txt");
                JudgeReturnDto compileDto = formatJudgeReturnDto(2, timeUsed, stderr, "编译失败", null, 0);
                JudgeRecord compileResult = BuildResult.buildResult(compileDto, testCases.get(0).getCaseId(), testCases.get(0).getInputData(), testCases.get(0).getExpectedOutput(), 1, language);
                compileResult.setTestTotal(testCases.size());
                return compileResult;
            }

            JudgeRecord lastResult = null;
            for(int i = 0; i < testCases.size(); i++) {
                TestDto testCase = testCases.get(i);

                writeInputToContainer(containerId,testCase.getInputData());

                long caseStart=System.currentTimeMillis();
                Integer runExitCode = executeScriptInContainer(containerId, "/workspace/run.sh");
                int timeUsed = (int) (System.currentTimeMillis() - caseStart);
                String stdout = readFileFromContainer(containerId, "/workspace/stdout.txt");
                String stderr = readFileFromContainer(containerId, "/workspace/stderr.txt");

                JudgeReturnDto judgeReturnDto = formatJudgeReturnDto(
                        runExitCode,
                        timeUsed,
                        stderr,
                        formatRuntimeError(stderr, language),
                        stdout,
                        0
                );

                JudgeRecord judgeRecord= BuildResult.buildResult(judgeReturnDto,testCase.getCaseId(),testCase.getInputData(),testCase.getExpectedOutput(),i+1,language);

                if (!"AC".equals(judgeRecord.getSubmitStatus())) {
                    judgeRecord.setFailIndex(i + 1);
                    judgeRecord.setTestTotal(testCases.size());
                    return judgeRecord;
                }
                lastResult = judgeRecord;
            }
            if (lastResult == null) {
                return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0),null,null,null,0,language);
            }
            lastResult.setSubmitStatus("AC");
            lastResult.setFailIndex(0);
            lastResult.setTestTotal(testCases.size());
            return lastResult;
        }finally {
            deleteDirectory(workspace);
            returnContainer(language, containerId);
        }
    }






    @Override
    public JudgeRecord batchExecuteCode(String code, String mainCode, String language, List<TestDto> testCases) throws IOException {
                if (testCases == null || testCases.isEmpty()) {
                    return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0), null, null, null, 0, language);
                }
                if (code == null || code.isBlank()) {
                    return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "代码不能为空", null, 0), testCases.get(0).getCaseId(), testCases.get(0).getInputData(), testCases.get(0).getExpectedOutput(), 1, language);
                }

                LanguageSpec spec = LanguageSpec.of(language);
                if (spec == null) {
                    return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "不支持的语言", null, 0), testCases.get(0).getCaseId(), testCases.get(0).getInputData(), testCases.get(0).getExpectedOutput(), 1, language);

                }

                String containerId = borrowContainer(language);
                if (containerId == null) {
                    return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "业务繁忙，请稍后重试", null, 0), testCases.get(0).getCaseId(), testCases.get(0).getInputData(), testCases.get(0).getExpectedOutput(), 1, language);
                }

                Path workspace = null;
                try {
                    cleanContainerWorkspace(containerId);
                    workspace = Files.createTempDirectory("codewise-judge-");
                    Files.writeString(
                            workspace.resolve("Solution.java"),
                            code,
                            StandardCharsets.UTF_8
                    );

                    Files.writeString(
                            workspace.resolve("Main.java"),
                            mainCode,
                            StandardCharsets.UTF_8
                    );

                    Files.writeString(
                            workspace.resolve("compile.sh"),
                            FUNCTION_JAVA_COMPILE_SCRIPT,
                            StandardCharsets.UTF_8
                    );

                    Files.writeString(
                            workspace.resolve("run.sh"),
                            FUNCTION_JAVA_RUN_SCRIPT,
                            StandardCharsets.UTF_8
                    );
                    writeFunctionBatchInputs(workspace, testCases);
                    Files.writeString(
                            workspace.resolve("run-batch.sh"),
                            FUNCTION_JAVA_BATCH_RUN_SCRIPT,
                            StandardCharsets.UTF_8
                    );

                    workspace.resolve("compile.sh")
                            .toFile()
                            .setExecutable(true);

                    workspace.resolve("run.sh")
                            .toFile()
                            .setExecutable(true);
                    workspace.resolve("run-batch.sh")
                            .toFile()
                            .setExecutable(true);

                    copyWorkspaceToContainer(containerId, workspace);

                    long compileStart = System.currentTimeMillis();
                    Integer compileExitCode = executeScriptInContainer(containerId, "/workspace/compile.sh", COMPILE_TIMEOUT_MS);
                    if (compileExitCode == null || compileExitCode != 0) {
                        int timeUsed = (int) (System.currentTimeMillis() - compileStart);
                        String stderr = readFileFromContainer(containerId, "/workspace/stderr.txt");
                        JudgeReturnDto compileDto = formatJudgeReturnDto(2, timeUsed, stderr, "编译失败", null, 0);
                        JudgeRecord compileResult = BuildResult.buildResult(compileDto, testCases.get(0).getCaseId(), testCases.get(0).getInputData(), testCases.get(0).getExpectedOutput(), 1, language);
                        compileResult.setTestTotal(testCases.size());
                        return compileResult;
                    }

                    long batchStart = System.currentTimeMillis();
                    Integer batchExitCode = executeFunctionBatchScript(
                            containerId,
                            testCases.size()
                    );
                    int batchTimeUsed = (int) (System.currentTimeMillis() - batchStart);
                    int averageTimeUsed = averageBatchTime(batchTimeUsed, testCases.size());
                    List<FunctionExecutionResult> executions = readFunctionBatchResults(containerId, batchExitCode, averageTimeUsed);
                    JudgeRecord lastResult = null;
                    for (int i = 0; i < testCases.size(); i++) {
                        TestDto testCase = testCases.get(i);
                        FunctionExecutionResult execution = i < executions.size()
                                ? executions.get(i)
                                : new FunctionExecutionResult(1, "", "", "批量执行未返回该测试用例结果", batchTimeUsed / testCases.size());
                        JudgeReturnDto judgeReturnDto = formatJudgeReturnDto(
                                execution.exitCode(),
                                execution.timeUsed(),
                                mergeFunctionLog(execution.stdout(), execution.stderr()),
                                formatRuntimeError(execution.stderr(), language),
                                execution.resultOutput(),
                                0
                        );
                        JudgeRecord judgeRecord = BuildResult.buildResult(
                                judgeReturnDto,
                                testCase.getCaseId(),
                                testCase.getInputData(),
                                testCase.getExpectedOutput(),
                                i + 1,
                                language
                        );

                        if (!"AC".equals(judgeRecord.getSubmitStatus())) {
                            judgeRecord.setFailIndex(i + 1);
                            judgeRecord.setTestTotal(testCases.size());
                            return judgeRecord;
                        }
                        lastResult = judgeRecord;
                    }
                    if (lastResult == null) {
                        return BuildResult.buildResult(formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0), null, null, null, 0, language);
                    }
                    lastResult.setSubmitStatus("AC");
                    lastResult.setFailIndex(0);
                    lastResult.setTestTotal(testCases.size());
                    return lastResult;
                } finally {
                    deleteDirectory(workspace);
                    returnContainer(language, containerId);
                }
    }


    @Override
    public List<JudgeRecord> batchDebugCode(
            String code,
            String mainCode,
            String language,
            List<TestDto> testCases
    ) throws IOException {
        if (testCases == null || testCases.isEmpty()) {
            return List.of(BuildResult.buildResult(
                    formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0),
                    null,
                    null,
                    null,
                    0,
                    language
            ));
        }
        if (code == null || code.isBlank()) {
            TestDto first = testCases.get(0);
            return List.of(BuildResult.buildResult(
                    formatJudgeReturnDto(null, 0, null, "代码不能为空", null, 0),
                    first.getCaseId(),
                    first.getInputData(),
                    first.getExpectedOutput(),
                    1,
                    language
            ));
        }
        if (mainCode == null || mainCode.isBlank()) {
            throw new IllegalArgumentException("函数模式主程序不能为空");
        }

        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            TestDto first = testCases.get(0);
            return List.of(BuildResult.buildResult(
                    formatJudgeReturnDto(null, 0, null, "不支持的语言", null, 0),
                    first.getCaseId(),
                    first.getInputData(),
                    first.getExpectedOutput(),
                    1,
                    language
            ));
        }

        String containerId = borrowContainer(language);
        if (containerId == null) {
            TestDto first = testCases.get(0);
            return List.of(BuildResult.buildResult(
                    formatJudgeReturnDto(null, 0, null, "业务繁忙，请稍后重试", null, 0),
                    first.getCaseId(),
                    first.getInputData(),
                    first.getExpectedOutput(),
                    1,
                    language
            ));
        }

        Path workspace = null;
        try {
            cleanContainerWorkspace(containerId);
            workspace = Files.createTempDirectory("codewise-function-debug-");
            Files.writeString(workspace.resolve("Solution.java"), code, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("Main.java"), mainCode, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("compile.sh"), FUNCTION_JAVA_COMPILE_SCRIPT, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("run.sh"), FUNCTION_JAVA_RUN_SCRIPT, StandardCharsets.UTF_8);
            writeFunctionBatchInputs(workspace, testCases);
            Files.writeString(workspace.resolve("run-batch.sh"), FUNCTION_JAVA_BATCH_RUN_SCRIPT, StandardCharsets.UTF_8);
            workspace.resolve("compile.sh").toFile().setExecutable(true);
            workspace.resolve("run.sh").toFile().setExecutable(true);
            workspace.resolve("run-batch.sh").toFile().setExecutable(true);
            copyWorkspaceToContainer(containerId, workspace);

            long compileStart = System.currentTimeMillis();
            Integer compileExitCode = executeScriptInContainer(containerId, "/workspace/compile.sh", COMPILE_TIMEOUT_MS);
            if (compileExitCode == null || compileExitCode != 0) {
                int timeUsed = (int) (System.currentTimeMillis() - compileStart);
                String stderr = readFileFromContainer(containerId, "/workspace/stderr.txt");
                TestDto first = testCases.getFirst();
                JudgeRecord compileResult = BuildResult.buildResult(
                        formatJudgeReturnDto(2, timeUsed, stderr, "编译失败", null, 0),
                        first.getCaseId(),
                        first.getInputData(),
                        first.getExpectedOutput(),
                        1,
                        language
                );
                compileResult.setTestTotal(testCases.size());
                return List.of(compileResult);
            }

            long batchStart = System.currentTimeMillis();
            Integer batchExitCode = executeFunctionBatchScript(
                    containerId,
                    testCases.size()
            );
            int batchTimeUsed = (int) (System.currentTimeMillis() - batchStart);
            int averageTimeUsed = averageBatchTime(batchTimeUsed, testCases.size());
            List<FunctionExecutionResult> executions = readFunctionBatchResults(containerId, batchExitCode, averageTimeUsed);
            List<JudgeRecord> results = new ArrayList<>(testCases.size());
            for (int index = 0; index < testCases.size(); index++) {
                TestDto testCase = testCases.get(index);
                FunctionExecutionResult execution = index < executions.size()
                        ? executions.get(index)
                        : new FunctionExecutionResult(1, "", "", "批量执行未返回该测试用例结果", batchTimeUsed);
                JudgeReturnDto judgeReturnDto = formatJudgeReturnDto(
                        execution.exitCode(),
                        execution.timeUsed(),
                        mergeFunctionLog(execution.stdout(), execution.stderr()),
                        formatRuntimeError(execution.stderr(), language),
                        execution.resultOutput(),
                        0
                );
                JudgeRecord result = BuildResult.buildResult(
                        judgeReturnDto,
                        testCase.getCaseId(),
                        testCase.getInputData(),
                        testCase.getExpectedOutput(),
                        index + 1,
                        language
                );
                result.setFailIndex(index + 1);
                result.setTestTotal(testCases.size());
                results.add(result);
            }
            return results;
        } finally {
            deleteDirectory(workspace);
            returnContainer(language, containerId);
        }
    }



    /**
     * 打包输入复制到docker
     */
    private void writeFunctionBatchInputs(Path workspace, List<TestDto> testCases) throws IOException {
        Path casesDirectory = Files.createDirectories(workspace.resolve("cases"));
        for (int index = 0; index < testCases.size(); index++) {
            String input = testCases.get(index).getInputData();
            Files.writeString(
                    casesDirectory.resolve("case-%06d.txt".formatted(index + 1)),
                    input == null ? "" : input,
                    StandardCharsets.UTF_8
            );
        }
    }

    private List<FunctionExecutionResult> readFunctionBatchResults(
            String containerId,
            Integer batchExitCode,
            int timeUsed
    ) {
        String batchOutput = readFileFromContainer(containerId, "/workspace/batch-result.txt");
        List<FunctionExecutionResult> results = new ArrayList<>();
        String beginMarker = "__CODEWISE_CASE_BEGIN__";
        String resultMarker = "__CODEWISE_RESULT_BEGIN__\n";
        String stdoutMarker = "__CODEWISE_STDOUT_BEGIN__\n";
        String stderrMarker = "__CODEWISE_STDERR_BEGIN__\n";
        String endMarker = "__CODEWISE_CASE_END__";

        for (String block : batchOutput.split(beginMarker, -1)) {
            if (block.isBlank()) {
                continue;
            }
            int firstNewLine = block.indexOf('\n');
            if (firstNewLine < 0) {
                continue;
            }
            int exitCode;
            try {
                exitCode = Integer.parseInt(block.substring(0, firstNewLine).trim());
            } catch (NumberFormatException exception) {
                exitCode = batchExitCode == null ? 1 : batchExitCode;
            }
            String record = block.substring(firstNewLine + 1);
            String resultOutput = readBatchSection(record, resultMarker, stdoutMarker);
            String stdout = readBatchSection(record, stdoutMarker, stderrMarker);
            String stderr = readBatchSection(record, stderrMarker, endMarker);
            results.add(new FunctionExecutionResult(exitCode, resultOutput, stdout, stderr, timeUsed));
        }
        return results;
    }

    private int averageBatchTime(int batchTimeUsed, int testCaseCount) {
        if (testCaseCount <= 0) {
            return batchTimeUsed;
        }
        return (int) Math.ceil((double) batchTimeUsed / testCaseCount);
    }

    private String readBatchSection(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        start += startMarker.length();
        int end = text.indexOf(endMarker, start);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end).stripTrailing();
    }

    private record FunctionExecutionResult(
            int exitCode,
            String resultOutput,
            String stdout,
            String stderr,
            int timeUsed
    ) {
    }

    private void writeInputToContainer(String containerId, String input) throws IOException {
        Path temp = Files.createTempDirectory("codewise-judge-input");
        try {
            Files.writeString(temp.resolve("input.txt"),input==null?"":input, StandardCharsets.UTF_8);
            copyWorkspaceToContainer(containerId, temp);
        }finally {
            deleteDirectory(temp);
        }
    }

    /**
     * 核心函数JAVA编译脚本
     */


    private static final String FUNCTION_JAVA_COMPILE_SCRIPT = """
        #!/bin/sh
        cd /workspace

        javac -cp "/opt/judge/lib/*" -encoding UTF-8 Main.java Solution.java 2> stderr.txt

        if [ $? -ne 0 ]; then
            echo 2 > exitcode.txt
            exit 2
        fi

        echo 0 > exitcode.txt
        """;

    private static final String FUNCTION_JAVA_RUN_SCRIPT = """
        #!/bin/sh
        cd /workspace

        rm -f result.txt
        timeout %ds java -cp "/workspace:/opt/judge/lib/*" -Xmx256m Main < input.txt > stdout.txt 2> stderr.txt

        code=$?
        echo $code > exitcode.txt
        exit $code
        """.formatted(TIME_LIMIT_MS / 1000);

    private static final String FUNCTION_JAVA_BATCH_RUN_SCRIPT = """
        #!/bin/sh
        cd /workspace
        rm -f batch-result.txt batch-process-stderr.txt

        case_count=$(find /workspace/cases -maxdepth 1 -type f -name 'case-*.txt' | wc -l)
        process_timeout=$((case_count * 4 + 5))
        timeout "${process_timeout}s" java \\
            -Dcodewise.timeLimitMs=__TIME_LIMIT_MS__ \\
            -cp "/workspace:/opt/judge/lib/*" \\
            -Xmx256m \\
            BatchMain > batch-result.txt 2> batch-process-stderr.txt
        code=$?

        if [ ! -s batch-result.txt ]; then
            printf '__CODEWISE_CASE_BEGIN__%s\\n' "$code" > batch-result.txt
            printf '__CODEWISE_TIME_MS__0\\n' >> batch-result.txt
            printf '__CODEWISE_RESULT_BEGIN__\\n\\n' >> batch-result.txt
            printf '__CODEWISE_STDOUT_BEGIN__\\n\\n' >> batch-result.txt
            printf '__CODEWISE_STDERR_BEGIN__\\n' >> batch-result.txt
            if [ -s batch-process-stderr.txt ]; then cat batch-process-stderr.txt >> batch-result.txt; fi
            printf '\\n__CODEWISE_CASE_END__\\n' >> batch-result.txt
        fi
        exit "$code"
        """.replace("__TIME_LIMIT_MS__", String.valueOf(TIME_LIMIT_MS));






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
                    });
                    //.awaitCompletion(2, TimeUnit.SECONDS);

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
    private JudgeReturnDto formatJudgeReturnDto(Integer exitCode,Integer timeUsed,String log,String errorMsg,String stdout,Integer memoryUsed) {
        return  JudgeReturnDto.builder().exitCode(exitCode).timeUsed(timeUsed).log(log).errorMsg(errorMsg).stdout(stdout).memoryUsed(memoryUsed).build();
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

    private String mergeFunctionLog(String stdout, String stderr) {
        String standardOutput = stdout == null ? "" : stdout.stripTrailing();
        String errorOutput = stderr == null ? "" : stderr.stripTrailing();
        if (standardOutput.isEmpty()) {
            return errorOutput;
        }
        if (errorOutput.isEmpty()) {
            return "标准输出:\n" + standardOutput;
        }
        return "标准输出:\n" + standardOutput + "\n错误输出:\n" + errorOutput;
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

    private void removeContainerQuietly(String containerId) {
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            log.info("删除容器成功: {}", containerId);
        } catch (Exception exception) {
            log.warn("删除容器失败: {}", containerId, exception);
        }
    }

    // ========== 清理容器工作目录 ==========
    private boolean cleanContainerWorkspace(String containerId) {
        try {
            // 删除 /workspace 下的所有文件
            var execCmd = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", "-c", "find /workspace -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +")
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            boolean completed = dockerClient.execStartCmd(execCmd.getId())
                    .exec(new ResultCallback.Adapter<>())
                    .awaitCompletion(2, TimeUnit.SECONDS);
            if (!completed) {
                log.warn("清理容器工作目录超时: {}", containerId);
                return false;
            }
            Integer exitCode = dockerClient.inspectExecCmd(execCmd.getId()).exec().getExitCode();
            if (exitCode == null || exitCode != 0) {
                log.warn("清理容器工作目录失败: containerId={}, exitCode={}", containerId, exitCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("清理容器工作目录失败", e);
            return false;
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
        return executeScriptInContainer(containerId, "/workspace/run.sh");
    }

    private Integer executeScriptInContainer(String containerId, String scriptPath) {
        return executeScriptInContainerWithTimeout(containerId, scriptPath, TIME_LIMIT_MS + 1000L);
    }

    private Integer executeFunctionBatchScript(String containerId, int testCaseCount) {
        long timeoutMillis = Math.max(
                TIME_LIMIT_MS + EXEC_OVERHEAD_MS,
                (TIME_LIMIT_MS + EXEC_OVERHEAD_MS) * testCaseCount + BATCH_STARTUP_TIMEOUT_MS
        );
        return executeScriptInContainerWithTimeout(containerId, "/workspace/run-batch.sh", timeoutMillis);
    }

    private Integer executeScriptInContainer(String containerId, String scriptPath, long timeoutMillis) {
        try {
            var execCmd = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", scriptPath)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            // 等待执行完成，如果超时未完成返回 false
            boolean completed = dockerClient.execStartCmd(execCmd.getId())
                    .exec(new ResultCallback.Adapter<>() {
                    })
                    .awaitCompletion(timeoutMillis, TimeUnit.MILLISECONDS);

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
    private Integer executeScriptInContainerWithTimeout(
            String containerId,
            String scriptPath,
            long timeoutMillis
    ) {
        try {
            var execCmd = dockerClient.execCreateCmd(containerId)
                    .withCmd("sh", scriptPath)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            boolean completed = dockerClient.execStartCmd(execCmd.getId())
                    .exec(new ResultCallback.Adapter<>())
                    .awaitCompletion(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                log.warn("容器批量执行超时");
                return 124;
            }
            Integer exitCode = dockerClient.inspectExecCmd(execCmd.getId()).exec().getExitCode();
            return exitCode != null ? exitCode : 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        } catch (Exception e) {
            log.error("批量执行命令失败", e);
            return 1;
        }
    }

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

    private void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            Files.walk(directory)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }



    // ========== 语言配置 ==========
    // ========== 语言配置 ==========
    private enum LanguageSpec {
        JAVA("java", "Main.java", "codewise-java-judge:17",
                """
                #!/bin/sh
                cd /workspace
                javac -cp "/opt/judge/lib/*" -encoding UTF-8 Main.java 2> stderr.txt
                if [ $? -ne 0 ]; then
                    echo 2 > exitcode.txt
                    exit 2
                fi
                echo 0 > exitcode.txt
                """,
                """
                #!/bin/sh
                cd /workspace
                timeout %ds java -cp "/workspace:/opt/judge/lib/*" -Xmx256m Main < input.txt > stdout.txt 2> stderr.txt
                code=$?
                echo $code > exitcode.txt
                exit $code
                """.formatted(TIME_LIMIT_MS / 1000)),

        PYTHON("python", "solution.py", "python:3.11-alpine",
                """
                #!/bin/sh
                cd /workspace
                echo 0 > exitcode.txt
                """,
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
                echo 0 > exitcode.txt
                """,
                """
                #!/bin/sh
                cd /workspace
                timeout %ds ./solution < input.txt > stdout.txt 2> stderr.txt
                echo $? > exitcode.txt
                """.formatted(TIME_LIMIT_MS / 1000)),

        C("c", "solution.c", "gcc:13",
                """
                #!/bin/sh
                cd /workspace
                gcc -O2 solution.c -o solution -lm 2> stderr.txt
                if [ $? -ne 0 ]; then
                    echo 2 > exitcode.txt
                    exit 2
                fi
                echo 0 > exitcode.txt
                """,
                """
                #!/bin/sh
                cd /workspace
                timeout %ds ./solution < input.txt > stdout.txt 2> stderr.txt
                echo $? > exitcode.txt
                """.formatted(TIME_LIMIT_MS / 1000));

        private final String language;
        private final String sourceFile;
        private final String image;
        private final String compileScript;
        private final String runScript;

        LanguageSpec(String language, String sourceFile, String image, String compileScript, String runScript) {
            this.language = language;
            this.sourceFile = sourceFile;
            this.image = image;
            this.compileScript = compileScript;
            this.runScript = runScript;
        }

        String sourceFile() {
            return sourceFile;
        }

        String buildCompileScript() {
            return compileScript;
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
