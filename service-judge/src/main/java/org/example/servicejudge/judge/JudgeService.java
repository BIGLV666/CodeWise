package org.example.servicejudge.judge;

import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.exception.BizException;
import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.Util.BuildResult;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.exception.JudgeErrorCode;
import org.example.servicejudge.interfaces.JudgeInterface;
import org.example.servicejudge.judge.container.ContainerPoolManager;
import org.example.servicejudge.judge.container.DockerExecTemplate;
import org.example.servicejudge.vo.DockersStatusVo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 判题门面：实现 {@link JudgeInterface} 的四个判题入口（单次运行、ACM 批量、
 * 函数批量、函数调试），编排「借容器 -> 准备工作区 -> 编译 -> 执行 -> 读结果 -> 还容器」
 * 的完整流程。
 *
 * <p>重构后的职责边界：容器借还/污染/扩缩容由 {@link ContainerPoolManager} 负责，
 * 纯 Docker I/O 由 {@link DockerExecTemplate} 提供，语言脚本与时限常量在
 * {@link LanguageSpec}，结果格式化在 {@link JudgeResults}。本类只保留判题编排
 * 与函数模式批量结果解析，保证既有调用方（Handler、DockerController）零改动。</p>
 *
 * <p>注意：判题编排内部包含 Docker 执行，任何调用方都不得将本类方法置于
 * 数据库事务内调用。</p>
 */
@Service
@Slf4j
public class JudgeService implements JudgeInterface {

    private final ContainerPoolManager containerPoolManager;
    private final DockerExecTemplate dockerExecTemplate;

    /** 批量结果末尾的峰值内存标记（KB）。 */
    private static final Pattern BATCH_MEMORY_PATTERN = Pattern.compile("__CODEWISE_MEM_KB__(\\d+)");

    public JudgeService(ContainerPoolManager containerPoolManager, DockerExecTemplate dockerExecTemplate) {
        this.containerPoolManager = containerPoolManager;
        this.dockerExecTemplate = dockerExecTemplate;
    }

    // ========== 容器管理门面方法（委托容器池管理器，调用方零改动） ==========

    /**
     * 查询各语言容器池状态。
     *
     * @return 每种语言一条状态记录
     * @throws org.example.serviceapi.exception.BizException 未登录、用户不存在或非管理员
     */
    public List<DockersStatusVo> getDockers() {
        return containerPoolManager.getDockers();
    }

    /**
     * 为指定语言扩容一个容器。
     *
     * @param language 语言标识
     */
    public void addDocker(String language) {
        containerPoolManager.addDocker(language);
    }

    /**
     * 删除指定语言的空闲容器。
     *
     * @param language    语言标识
     * @param containerId 容器 ID
     * @return 删除成功返回 true
     */
    public boolean removeDocker(String language, String containerId) {
        return containerPoolManager.removeDocker(language, containerId);
    }

    // ========== 判题主方法 ==========

    /**
     * 单次运行用户代码（调试/单用例场景）。
     *
     * @param code     用户代码
     * @param language 语言标识
     * @param input    标准输入内容，null 视为空串
     * @return 执行结果（退出码/输出/内存/耗时/错误信息）
     */
    @Override
    public JudgeReturnDto executeCode(String code, String language, String input) {
        // 默认失败索引为 0
        if (code == null || code.isBlank()) {
            return JudgeResults.formatJudgeReturnDto(null, 0, null, "代码不能为空", null, 0);
        }

        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            return JudgeResults.formatJudgeReturnDto(null, 0, null, "不支持的语言", null, 0);
        }

        String containerId = containerPoolManager.borrowContainer(language);

        if (containerId == null) {
            return JudgeResults.formatJudgeReturnDto(null, 0, null, "业务繁忙请稍后重试", null, 0);
        }
        long start = System.currentTimeMillis();

        try {
            dockerExecTemplate.cleanContainerWorkspace(containerId);
            Path workspace = Files.createTempDirectory("codewise-judge-");
            try {
                Files.writeString(workspace.resolve(spec.sourceFile()), code, StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("input.txt"), input == null ? "" : input, StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("compile.sh"), spec.buildCompileScript(), StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("run.sh"), spec.buildRunScript(), StandardCharsets.UTF_8);
                workspace.resolve("compile.sh").toFile().setExecutable(true);
                workspace.resolve("run.sh").toFile().setExecutable(true);

                dockerExecTemplate.copyWorkspaceToContainer(containerId, workspace);
                Integer compileExitCode = dockerExecTemplate.executeScriptInContainerWithTimeout(
                        containerId,
                        "/workspace/compile.sh",
                        LanguageSpec.COMPILE_TIMEOUT_MS,
                        containerPoolManager::markTainted);
                if (compileExitCode == null || compileExitCode != 0) {
                    int timeUsed = (int) (System.currentTimeMillis() - start);
                    String stderr = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/stderr.txt");
                    return JudgeResults.formatJudgeReturnDto(2, timeUsed, stderr, "编译失败", null, 0);
                }

                Integer exitCode = dockerExecTemplate.executeScriptInContainer(
                        containerId,
                        "/workspace/run.sh",
                        containerPoolManager::markTainted);
                int timeUsed = (int) (System.currentTimeMillis() - start);

                String stdout = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/stdout.txt");
                String stderr = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/stderr.txt");

                // exitcode.txt 首行是退出码、次行是本次运行的峰值内存（KB，脚本内 GNU time 产出）
                String[] runMetrics = readRunMetricsLines(containerId);

                // 如果 exec 没有返回 124（TLE），我们再尝试从文件读取以确保准确捕获 Java/Python 内部抛出的异常退出码
                if (exitCode == null || exitCode != 124) {
                    if (!runMetrics[0].isEmpty()) {
                        try {
                            exitCode = Integer.parseInt(runMetrics[0]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                int memoryUsed = parsePeakMemoryKb(runMetrics[1], containerId);
                String cleanErr = JudgeResults.formatRuntimeError(stderr, language);
                JudgeReturnDto judgeReturnDto = new JudgeReturnDto();
                judgeReturnDto.setExitCode(exitCode);
                judgeReturnDto.setStdout(stdout);
                judgeReturnDto.setLog(stderr);
                judgeReturnDto.setMemoryUsed(memoryUsed);
                judgeReturnDto.setTimeUsed(timeUsed);
                judgeReturnDto.setErrorMsg(cleanErr);
                return judgeReturnDto;
            } finally {
                DockerExecTemplate.deleteDirectory(workspace);
            }
        } catch (Exception e) {
            log.error("判题系统内部错误", e);
            int timeUsed = (int) (System.currentTimeMillis() - start);
            return JudgeResults.formatJudgeReturnDto(null, null, "判题系统内部错误:" + e.getMessage(),
                    "判题系统内部错误", null, null);
        } finally {
            containerPoolManager.returnContainer(language, containerId);
        }
    }

    /**
     * ACM 模式批量判题：逐用例在同一容器内编译一次、运行多次，
     * 首个非 AC 用例即短路返回。
     *
     * @param code      用户代码
     * @param language  语言标识
     * @param testCases 测试用例列表
     * @return 判题结果记录（未落库）
     * @throws IOException 工作区准备失败
     */
    @Override
    public JudgeRecord batchExecuteCode(String code, String language, List<TestDto> testCases) throws IOException {
        if (testCases == null || testCases.isEmpty()) {
            return BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0), null, null, null, 0,
                    language);
        }
        if (code == null || code.isBlank()) {
            return BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "代码不能为空", null, 0),
                    testCases.get(0).getCaseId(), testCases.get(0).getInputData(),
                    testCases.get(0).getExpectedOutput(), 1, language);
        }

        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            return BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "不支持的语言", null, 0),
                    testCases.get(0).getCaseId(), testCases.get(0).getInputData(),
                    testCases.get(0).getExpectedOutput(), 1, language);
        }

        String containerId = containerPoolManager.borrowContainer(language);
        if (containerId == null) {
            return BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "业务繁忙，请稍后重试", null, 0),
                    testCases.get(0).getCaseId(), testCases.get(0).getInputData(),
                    testCases.get(0).getExpectedOutput(), 1, language);
        }

        Path workspace = null;
        try {
            dockerExecTemplate.cleanContainerWorkspace(containerId);
            workspace = Files.createTempDirectory("codewise-judge-");
            Files.writeString(workspace.resolve(spec.sourceFile()), code, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("compile.sh"), spec.buildCompileScript(), StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("run.sh"), spec.buildRunScript(), StandardCharsets.UTF_8);
            workspace.resolve("compile.sh").toFile().setExecutable(true);
            workspace.resolve("run.sh").toFile().setExecutable(true);

            dockerExecTemplate.copyWorkspaceToContainer(containerId, workspace);

            long compileStart = System.currentTimeMillis();
            Integer compileExitCode = dockerExecTemplate.executeScriptInContainerWithTimeout(
                    containerId,
                    "/workspace/compile.sh",
                    LanguageSpec.COMPILE_TIMEOUT_MS,
                    containerPoolManager::markTainted);
            if (compileExitCode == null || compileExitCode != 0) {
                int timeUsed = (int) (System.currentTimeMillis() - compileStart);
                String stderr = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/stderr.txt");
                JudgeReturnDto compileDto = JudgeResults.formatJudgeReturnDto(2, timeUsed, stderr, "编译失败", null, 0);
                JudgeRecord compileResult = BuildResult.buildResult(compileDto, testCases.get(0).getCaseId(),
                        testCases.get(0).getInputData(), testCases.get(0).getExpectedOutput(), 1, language);
                compileResult.setTestTotal(testCases.size());
                return compileResult;
            }

            JudgeRecord lastResult = null;
            for (int i = 0; i < testCases.size(); i++) {
                TestDto testCase = testCases.get(i);

                dockerExecTemplate.writeInputToContainer(containerId, testCase.getInputData());

                long caseStart = System.currentTimeMillis();
                Integer runExitCode = dockerExecTemplate.executeScriptInContainer(
                        containerId,
                        "/workspace/run.sh",
                        containerPoolManager::markTainted);
                int timeUsed = (int) (System.currentTimeMillis() - caseStart);
                String stdout = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/stdout.txt");
                String stderr = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/stderr.txt");
                int memoryUsed = readPeakMemoryKb(containerId);

                JudgeReturnDto judgeReturnDto = JudgeResults.formatJudgeReturnDto(
                        runExitCode,
                        timeUsed,
                        stderr,
                        JudgeResults.formatRuntimeError(stderr, language),
                        stdout,
                        memoryUsed
                );

                JudgeRecord judgeRecord = BuildResult.buildResult(judgeReturnDto, testCase.getCaseId(),
                        testCase.getInputData(), testCase.getExpectedOutput(), i + 1, language);

                if (!"AC".equals(judgeRecord.getSubmitStatus())) {
                    judgeRecord.setFailIndex(i + 1);
                    judgeRecord.setTestTotal(testCases.size());
                    return judgeRecord;
                }
                lastResult = judgeRecord;
            }
            if (lastResult == null) {
                return BuildResult.buildResult(
                        JudgeResults.formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0), null, null, null,
                        0, language);
            }
            lastResult.setSubmitStatus("AC");
            lastResult.setFailIndex(0);
            lastResult.setTestTotal(testCases.size());
            return lastResult;
        } finally {
            DockerExecTemplate.deleteDirectory(workspace);
            containerPoolManager.returnContainer(language, containerId);
        }
    }

    /**
     * 函数模式批量判题：同一 JVM 顺序执行全部用例，首个非 AC 用例短路返回。
     *
     * @param code      用户函数代码（经 CodeBuild 包装）
     * @param mainCode  生成的 Main.java 调用器代码
     * @param language  语言标识（当前仅支持 java）
     * @param testCases 测试用例列表
     * @return 判题结果记录（未落库）
     * @throws IOException 工作区准备失败
     */
    @Override
    public JudgeRecord batchExecuteCode(String code, String mainCode, String language, List<TestDto> testCases)
            throws IOException {
        if (testCases == null || testCases.isEmpty()) {
            return BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0), null, null, null, 0,
                    language);
        }
        if (code == null || code.isBlank()) {
            return BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "代码不能为空", null, 0),
                    testCases.get(0).getCaseId(), testCases.get(0).getInputData(),
                    testCases.get(0).getExpectedOutput(), 1, language);
        }

        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            return BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "不支持的语言", null, 0),
                    testCases.get(0).getCaseId(), testCases.get(0).getInputData(),
                    testCases.get(0).getExpectedOutput(), 1, language);
        }

        String containerId = containerPoolManager.borrowContainer(language);
        if (containerId == null) {
            return BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "业务繁忙，请稍后重试", null, 0),
                    testCases.get(0).getCaseId(), testCases.get(0).getInputData(),
                    testCases.get(0).getExpectedOutput(), 1, language);
        }

        Path workspace = null;
        try {
            dockerExecTemplate.cleanContainerWorkspace(containerId);
            workspace = Files.createTempDirectory("codewise-judge-");
            Files.writeString(workspace.resolve("Solution.java"), code, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("Main.java"), mainCode, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("compile.sh"), LanguageSpec.FUNCTION_JAVA_COMPILE_SCRIPT,
                    StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("run.sh"), LanguageSpec.FUNCTION_JAVA_RUN_SCRIPT,
                    StandardCharsets.UTF_8);
            writeFunctionBatchInputs(workspace, testCases);
            Files.writeString(workspace.resolve("run-batch.sh"), LanguageSpec.FUNCTION_JAVA_BATCH_RUN_SCRIPT,
                    StandardCharsets.UTF_8);

            workspace.resolve("compile.sh").toFile().setExecutable(true);
            workspace.resolve("run.sh").toFile().setExecutable(true);
            workspace.resolve("run-batch.sh").toFile().setExecutable(true);

            dockerExecTemplate.copyWorkspaceToContainer(containerId, workspace);

            long compileStart = System.currentTimeMillis();
            Integer compileExitCode = dockerExecTemplate.executeScriptInContainerWithTimeout(
                    containerId,
                    "/workspace/compile.sh",
                    LanguageSpec.COMPILE_TIMEOUT_MS,
                    containerPoolManager::markTainted);
            if (compileExitCode == null || compileExitCode != 0) {
                int timeUsed = (int) (System.currentTimeMillis() - compileStart);
                String stderr = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/stderr.txt");
                JudgeReturnDto compileDto = JudgeResults.formatJudgeReturnDto(2, timeUsed, stderr, "编译失败", null, 0);
                JudgeRecord compileResult = BuildResult.buildResult(compileDto, testCases.get(0).getCaseId(),
                        testCases.get(0).getInputData(), testCases.get(0).getExpectedOutput(), 1, language);
                compileResult.setTestTotal(testCases.size());
                return compileResult;
            }

            long batchStart = System.currentTimeMillis();
            Integer batchExitCode = dockerExecTemplate.executeFunctionBatchScript(
                    containerId,
                    testCases.size(),
                    containerPoolManager::markTainted);
            int batchTimeUsed = (int) (System.currentTimeMillis() - batchStart);
            int averageTimeUsed = averageBatchTime(batchTimeUsed, testCases.size());
            List<FunctionExecutionResult> executions = readFunctionBatchResults(containerId, batchExitCode,
                    averageTimeUsed);
            JudgeRecord lastResult = null;
            for (int i = 0; i < testCases.size(); i++) {
                TestDto testCase = testCases.get(i);
                FunctionExecutionResult execution = i < executions.size()
                        ? executions.get(i)
                        : new FunctionExecutionResult(1, "", "", "批量执行未返回该测试用例结果",
                                batchTimeUsed / testCases.size(), 0);
                JudgeReturnDto judgeReturnDto = JudgeResults.formatJudgeReturnDto(
                        execution.exitCode(),
                        execution.timeUsed(),
                        JudgeResults.mergeFunctionLog(execution.stdout(), execution.stderr()),
                        JudgeResults.formatRuntimeError(execution.stderr(), language),
                        execution.resultOutput(),
                        execution.memoryKb()
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
                return BuildResult.buildResult(
                        JudgeResults.formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0), null, null, null,
                        0, language);
            }
            lastResult.setSubmitStatus("AC");
            lastResult.setFailIndex(0);
            lastResult.setTestTotal(testCases.size());
            return lastResult;
        } finally {
            DockerExecTemplate.deleteDirectory(workspace);
            containerPoolManager.returnContainer(language, containerId);
        }
    }

    /**
     * 函数模式调试判题：跑完所有用例并逐条返回结果（不短路），供前端展示每个用例的输出。
     *
     * @param code      用户函数代码（经 CodeBuild 包装）
     * @param mainCode  生成的 Main.java 调用器代码
     * @param language  语言标识（当前仅支持 java）
     * @param testCases 测试用例列表
     * @return 每个用例一条判题记录
     * @throws IOException     工作区准备失败
     * @throws BizException 主程序为空
     */
    @Override
    public List<JudgeRecord> batchDebugCode(
            String code,
            String mainCode,
            String language,
            List<TestDto> testCases
    ) throws IOException {
        if (testCases == null || testCases.isEmpty()) {
            return List.of(BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "测试用例为空", null, 0),
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
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "代码不能为空", null, 0),
                    first.getCaseId(),
                    first.getInputData(),
                    first.getExpectedOutput(),
                    1,
                    language
            ));
        }
        if (mainCode == null || mainCode.isBlank()) {
            throw new BizException(JudgeErrorCode.FUNCTION_MAIN_MISSING);
        }

        LanguageSpec spec = LanguageSpec.of(language);
        if (spec == null) {
            TestDto first = testCases.get(0);
            return List.of(BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "不支持的语言", null, 0),
                    first.getCaseId(),
                    first.getInputData(),
                    first.getExpectedOutput(),
                    1,
                    language
            ));
        }

        String containerId = containerPoolManager.borrowContainer(language);
        if (containerId == null) {
            TestDto first = testCases.get(0);
            return List.of(BuildResult.buildResult(
                    JudgeResults.formatJudgeReturnDto(null, 0, null, "业务繁忙，请稍后重试", null, 0),
                    first.getCaseId(),
                    first.getInputData(),
                    first.getExpectedOutput(),
                    1,
                    language
            ));
        }

        Path workspace = null;
        try {
            dockerExecTemplate.cleanContainerWorkspace(containerId);
            workspace = Files.createTempDirectory("codewise-function-debug-");
            Files.writeString(workspace.resolve("Solution.java"), code, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("Main.java"), mainCode, StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("compile.sh"), LanguageSpec.FUNCTION_JAVA_COMPILE_SCRIPT,
                    StandardCharsets.UTF_8);
            Files.writeString(workspace.resolve("run.sh"), LanguageSpec.FUNCTION_JAVA_RUN_SCRIPT,
                    StandardCharsets.UTF_8);
            writeFunctionBatchInputs(workspace, testCases);
            Files.writeString(workspace.resolve("run-batch.sh"), LanguageSpec.FUNCTION_JAVA_BATCH_RUN_SCRIPT,
                    StandardCharsets.UTF_8);
            workspace.resolve("compile.sh").toFile().setExecutable(true);
            workspace.resolve("run.sh").toFile().setExecutable(true);
            workspace.resolve("run-batch.sh").toFile().setExecutable(true);
            dockerExecTemplate.copyWorkspaceToContainer(containerId, workspace);

            long compileStart = System.currentTimeMillis();
            Integer compileExitCode = dockerExecTemplate.executeScriptInContainerWithTimeout(
                    containerId,
                    "/workspace/compile.sh",
                    LanguageSpec.COMPILE_TIMEOUT_MS,
                    containerPoolManager::markTainted);
            if (compileExitCode == null || compileExitCode != 0) {
                int timeUsed = (int) (System.currentTimeMillis() - compileStart);
                String stderr = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/stderr.txt");
                TestDto first = testCases.getFirst();
                JudgeRecord compileResult = BuildResult.buildResult(
                        JudgeResults.formatJudgeReturnDto(2, timeUsed, stderr, "编译失败", null, 0),
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
            Integer batchExitCode = dockerExecTemplate.executeFunctionBatchScript(
                    containerId,
                    testCases.size(),
                    containerPoolManager::markTainted);
            int batchTimeUsed = (int) (System.currentTimeMillis() - batchStart);
            int averageTimeUsed = averageBatchTime(batchTimeUsed, testCases.size());
            List<FunctionExecutionResult> executions = readFunctionBatchResults(containerId, batchExitCode,
                    averageTimeUsed);
            List<JudgeRecord> results = new ArrayList<>(testCases.size());
            for (int index = 0; index < testCases.size(); index++) {
                TestDto testCase = testCases.get(index);
                FunctionExecutionResult execution = index < executions.size()
                        ? executions.get(index)
                        : new FunctionExecutionResult(1, "", "", "批量执行未返回该测试用例结果", batchTimeUsed, 0);
                JudgeReturnDto judgeReturnDto = JudgeResults.formatJudgeReturnDto(
                        execution.exitCode(),
                        execution.timeUsed(),
                        JudgeResults.mergeFunctionLog(execution.stdout(), execution.stderr()),
                        JudgeResults.formatRuntimeError(execution.stderr(), language),
                        execution.resultOutput(),
                        execution.memoryKb()
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
            DockerExecTemplate.deleteDirectory(workspace);
            containerPoolManager.returnContainer(language, containerId);
        }
    }

    // ========== 函数模式批量执行辅助 ==========

    /**
     * 将全部用例输入打包写入工作区 cases/case-XXXXXX.txt。
     *
     * <p>每个文件都以换行结尾：函数模式的 Main 用 {@code readLine()} 读参数，
     * 单参数且取值为空串时（完全合法的边界用例，如空字符串）文件若是 0 字节，
     * {@code readLine()} 会返回 null，被测代码随即 NPE——整个批量会因此提前中断，
     * 调用方只看到「执行结果数量不完整」。补一个换行让空串读成 ""，而不是 null。</p>
     */
    static void writeFunctionBatchInputs(Path workspace, List<TestDto> testCases) throws IOException {
        Path casesDirectory = Files.createDirectories(workspace.resolve("cases"));
        for (int index = 0; index < testCases.size(); index++) {
            String input = testCases.get(index).getInputData();
            Files.writeString(
                    casesDirectory.resolve("case-%06d.txt".formatted(index + 1)),
                    (input == null ? "" : input) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
        }
    }

    /**
     * 解析批量脚本输出的 batch-result.txt，按标记切分出每个用例的退出码、
     * 结果输出、标准输出与错误输出。
     */
    private List<FunctionExecutionResult> readFunctionBatchResults(
            String containerId,
            Integer batchExitCode,
            int timeUsed
    ) {
        String batchOutput = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/batch-result.txt");
        int batchMemoryKb = parseBatchMemoryKb(batchOutput, containerId);
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
            results.add(new FunctionExecutionResult(exitCode, resultOutput, stdout, stderr, timeUsed, batchMemoryKb));
        }
        return results;
    }

    /** 批量总耗时折算为单用例平均耗时（向上取整）。 */
    private int averageBatchTime(int batchTimeUsed, int testCaseCount) {
        if (testCaseCount <= 0) {
            return batchTimeUsed;
        }
        return (int) Math.ceil((double) batchTimeUsed / testCaseCount);
    }

    /** 截取批量结果记录中两个标记之间的文本段。 */
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

    /**
     * 函数模式单用例批量执行结果。
     *
     * @param memoryKb 整批 JVM 的峰值内存（KB），同一批次的每个用例取同一个值
     *                 （与「批量总耗时按用例平均」的口径一致）
     */
    private record FunctionExecutionResult(
            int exitCode,
            String resultOutput,
            String stdout,
            String stderr,
            int timeUsed,
            int memoryKb
    ) {
    }

    /**
     * 读取 run.sh 写入的 exitcode.txt 并切成两行：{@code [0]} 退出码、{@code [1]} 峰值内存（KB）。
     * 文件缺失或只有一行时对应位置为空串（镜像内无 GNU time 时第二行就是空的）。
     */
    private String[] readRunMetricsLines(String containerId) {
        String content = dockerExecTemplate.readFileFromContainer(containerId, "/workspace/exitcode.txt");
        String[] lines = content == null ? new String[0] : content.strip().split("\\R", -1);
        String exitCode = lines.length > 0 ? lines[0].trim() : "";
        String memoryKb = lines.length > 1 ? lines[1].trim() : "";
        return new String[]{exitCode, memoryKb};
    }

    /** 读取本次运行的峰值内存（KB）；缺失时返回 0 并告警。 */
    private int readPeakMemoryKb(String containerId) {
        return parsePeakMemoryKb(readRunMetricsLines(containerId)[1], containerId);
    }

    /** 从批量结果末尾的 {@code __CODEWISE_MEM_KB__} 标记取整批峰值内存（KB）。 */
    private int parseBatchMemoryKb(String batchOutput, String containerId) {
        Matcher matcher = BATCH_MEMORY_PATTERN.matcher(batchOutput == null ? "" : batchOutput);
        if (!matcher.find()) {
            return parsePeakMemoryKb("", containerId);
        }
        return parsePeakMemoryKb(matcher.group(1), containerId);
    }

    /**
     * 解析峰值内存（KB），缺失或非法时记 0 并告警。
     *
     * <p>沙箱镜像里没有 {@code /usr/bin/time} 时脚本无法产出该值，属于镜像配置问题，
     * 不影响判题结论，只让内存展示缺失，因此这里不抛异常。</p>
     */
    private int parsePeakMemoryKb(String raw, String containerId) {
        if (raw == null || raw.isEmpty()) {
            log.warn("判题脚本未返回峰值内存，请确认沙箱镜像内置 /usr/bin/time: containerId={}", containerId);
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException exception) {
            log.warn("峰值内存解析失败: containerId={}, value={}", containerId, raw);
            return 0;
        }
    }
}
