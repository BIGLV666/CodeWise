package org.example.servicejudge.judge;

import org.example.servicejudge.Dto.TestDto;
import org.example.servicejudge.entry.FunctionTestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 函数模式批量用例输入文件的落盘格式。
 *
 * <p>关键点是「单参数空串」：文件必须是含一个空行的 1 字节文件，否则 {@code readLine()}
 * 返回 null，被调用方 NPE 后整个批量提前中断。</p>
 */
class JudgeServiceBatchInputTest {

    private static TestDto testCase(String input) {
        FunctionTestCase testCase = new FunctionTestCase();
        testCase.setInputData(input);
        testCase.setExpectedOutput("ignored");
        return new TestDto(testCase);
    }

    @Test
    void writesEveryCaseFileWithTrailingNewline(@TempDir Path workspace) throws Exception {
        JudgeService.writeFunctionBatchInputs(workspace, List.of(
                testCase(""),
                testCase("[1,2]\n3"),
                testCase(null)
        ));

        Path cases = workspace.resolve("cases");
        assertEquals(System.lineSeparator(),
                Files.readString(cases.resolve("case-000001.txt"), StandardCharsets.UTF_8));
        assertEquals("[1,2]\n3" + System.lineSeparator(),
                Files.readString(cases.resolve("case-000002.txt"), StandardCharsets.UTF_8));
        assertEquals(System.lineSeparator(),
                Files.readString(cases.resolve("case-000003.txt"), StandardCharsets.UTF_8));
    }
}
