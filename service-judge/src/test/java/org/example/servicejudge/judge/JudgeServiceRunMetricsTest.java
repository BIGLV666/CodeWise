package org.example.servicejudge.judge;

import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.TestCase;
import org.example.servicejudge.judge.container.ContainerPoolManager;
import org.example.servicejudge.judge.container.DockerExecTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 判题内存指标解析测试：四条路径（ACM 单次运行、ACM 批量、函数批量提交、函数批量调试）
 * 都要把脚本产出的真实峰值写进判题记录；脚本没产出该值时降级为 0，且不影响判题结论。
 *
 * <p>容器内文件读写与脚本执行由 {@link DockerExecTemplate} 的 mock 提供，这里只验证
 * 「脚本产出 → 判题记录」这段解析逻辑（真实进程行为由线上探针验证）。</p>
 */
class JudgeServiceRunMetricsTest {

    private static final String CONTAINER_ID = "judge-container-1";

    private DockerExecTemplate dockerExecTemplate;
    private JudgeService judgeService;

    /** 容器内文件内容（按容器内绝对路径）。 */
    private final Map<String, String> containerFiles = new HashMap<>();

    @BeforeEach
    void setUp() {
        ContainerPoolManager containerPoolManager = mock(ContainerPoolManager.class);
        dockerExecTemplate = mock(DockerExecTemplate.class);
        judgeService = new JudgeService(containerPoolManager, dockerExecTemplate);

        when(containerPoolManager.borrowContainer(anyString())).thenReturn(CONTAINER_ID);
        when(dockerExecTemplate.cleanContainerWorkspace(CONTAINER_ID)).thenReturn(true);
        when(dockerExecTemplate.readFileFromContainer(eq(CONTAINER_ID), anyString()))
                .thenAnswer(invocation -> containerFiles.getOrDefault(invocation.getArgument(1), ""));
        when(dockerExecTemplate.executeScriptInContainerWithTimeout(
                eq(CONTAINER_ID), anyString(), anyLong(), any())).thenReturn(0);
        when(dockerExecTemplate.executeScriptInContainer(eq(CONTAINER_ID), anyString(), any())).thenReturn(0);
        when(dockerExecTemplate.executeFunctionBatchScript(eq(CONTAINER_ID), anyInt(), any())).thenReturn(0);
    }

    @Test
    void singleRunShouldUsePeakMemoryFromExitCodeFile() {
        containerFiles.put("/workspace/stdout.txt", "3\n");
        containerFiles.put("/workspace/stderr.txt", "");
        containerFiles.put("/workspace/exitcode.txt", "0\n111744\n");

        JudgeReturnDto result = judgeService.executeCode("class Main{}", "java", "1\n");

        assertEquals(0, result.getExitCode());
        assertEquals(111744, result.getMemoryUsed());
    }

    @Test
    void singleRunShouldStillReportPeakMemoryOnTimeout() {
        when(dockerExecTemplate.executeScriptInContainer(eq(CONTAINER_ID), anyString(), any())).thenReturn(124);
        containerFiles.put("/workspace/exitcode.txt", "124\n34508\n");

        JudgeReturnDto result = judgeService.executeCode("class Main{}", "java", "1\n");

        assertEquals(124, result.getExitCode());
        assertEquals(34508, result.getMemoryUsed(), "TLE 也应有峰值内存（GNU time 对被杀进程同样报告 %M）");
    }

    @Test
    void singleRunShouldReportZeroMemoryWhenSandboxHasNoGnuTime() {
        containerFiles.put("/workspace/stdout.txt", "3\n");
        containerFiles.put("/workspace/exitcode.txt", "0\n");

        JudgeReturnDto result = judgeService.executeCode("class Main{}", "java", "1\n");

        assertEquals(0, result.getExitCode(), "只有一行时退出码仍应正常解析");
        assertEquals(0, result.getMemoryUsed());
    }

    @Test
    void singleRunShouldTolerateLegacySingleLineExitCodeFile() {
        containerFiles.put("/workspace/stdout.txt", "3\n");
        containerFiles.put("/workspace/exitcode.txt", "0");

        JudgeReturnDto result = judgeService.executeCode("class Main{}", "java", "1\n");

        assertEquals(0, result.getExitCode());
        assertEquals(0, result.getMemoryUsed());
    }

    @Test
    void acmBatchShouldReadPeakMemoryPerCase() throws IOException {
        // 用例 1 通过、用例 2 运行错误：返回的应是用例 2 自己的峰值，证明逐用例重新读取了 exitcode.txt
        AtomicInteger exitCodeReads = new AtomicInteger();
        when(dockerExecTemplate.readFileFromContainer(eq(CONTAINER_ID), eq("/workspace/exitcode.txt")))
                .thenAnswer(invocation -> exitCodeReads.getAndIncrement() == 0 ? "0\n1000\n" : "3\n2000\n");
        when(dockerExecTemplate.executeScriptInContainer(eq(CONTAINER_ID), anyString(), any()))
                .thenReturn(0, 3);
        containerFiles.put("/workspace/stdout.txt", "1\n");

        JudgeRecord record = judgeService.batchExecuteCode("class Main{}", "java", testCases(2));

        assertEquals("RE", record.getSubmitStatus());
        assertEquals(2, record.getFailIndex(), "应在第二个用例短路");
        assertEquals(2000, record.getMemoryUsed());
    }

    @Test
    void functionDebugShouldApplyBatchPeakMemoryToEveryCase() throws IOException {
        containerFiles.put("/workspace/batch-result.txt",
                caseSection("0", "1") + caseSection("0", "2") + "__CODEWISE_MEM_KB__45678\n");

        List<JudgeRecord> records = judgeService.batchDebugCode(
                "class Solution{}", "class Main{}", "java", testCases(2));

        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(record -> record.getMemoryUsed() == 45678),
                "函数模式内存按整批峰值下发（与批量时间取平均同口径）");
    }

    @Test
    void functionSubmitShouldCarryBatchPeakMemory() throws IOException {
        containerFiles.put("/workspace/batch-result.txt",
                caseSection("0", "1") + caseSection("0", "1") + "__CODEWISE_MEM_KB__32768\n");

        JudgeRecord record = judgeService.batchExecuteCode(
                "class Solution{}", "class Main{}", "java", testCases(2));

        assertEquals("AC", record.getSubmitStatus());
        assertEquals(32768, record.getMemoryUsed());
    }

    @Test
    void functionBatchShouldReportZeroMemoryWhenMarkerMissing() throws IOException {
        containerFiles.put("/workspace/batch-result.txt",
                caseSection("0", "1") + caseSection("0", "2"));

        List<JudgeRecord> records = judgeService.batchDebugCode(
                "class Solution{}", "class Main{}", "java", testCases(2));

        assertEquals(2, records.size());
        assertTrue(records.stream().allMatch(record -> record.getMemoryUsed() == 0));
        assertNotNull(records.get(0).getSubmitStatus(), "缺少内存字段不应影响判题结论");
    }

    private List<TestDto> testCases(int count) {
        List<TestDto> testCases = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            testCases.add(new TestDto(TestCase.builder()
                    .caseId((long) index)
                    .questionId(1L)
                    .inputData(index + "\n")
                    .expectedOutput("1\n")
                    .build()));
        }
        return testCases;
    }

    /** 与生成代码 {@code BatchMain.writeCaseResult} 的输出格式一致。 */
    private String caseSection(String exitCode, String result) {
        return "__CODEWISE_CASE_BEGIN__" + exitCode + "\n"
                + "__CODEWISE_TIME_MS__5\n"
                + "__CODEWISE_RESULT_BEGIN__\n"
                + result + "\n__CODEWISE_STDOUT_BEGIN__\n"
                + "\n__CODEWISE_STDERR_BEGIN__\n"
                + "\n__CODEWISE_CASE_END__\n";
    }
}
