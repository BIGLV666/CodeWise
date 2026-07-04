package org.example.servicejudge.Util;

import org.example.servicejudge.Dto.JudgeReturnDto;
import org.example.servicejudge.entry.JudgeRecord;
import org.example.servicejudge.entry.TestCase;

import java.util.Arrays;
import java.util.stream.Collectors;

public class BuildResult {
    public static JudgeRecord buildResult(JudgeReturnDto judgeReturnDto, Long testId,String input,String output, Integer failIndex,String language) {
        // ============ 核心改造部分：友好的错误信息处理 ============
        Integer exitCode= judgeReturnDto.getExitCode();
        String stdout= judgeReturnDto.getStdout();
        Integer timeUsed= judgeReturnDto.getTimeUsed();
        Integer memoryUsed= judgeReturnDto.getMemoryUsed();
        Long testCaseId=null;
        if(testId!=null){
        testCaseId =testId;
        }
        String stderr=judgeReturnDto.getLog();

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

    }
    private static JudgeRecord buildResult(String status, String expected, String actual,
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
    private static String formatCompileError(String stderr) {
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
     * 格式化答案错误 (WA)，类似力扣展示
     */
    private static String formatWrongAnswerError(String input, String expected, String actual) {
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


    /**
     * 格式化运行时错误 (RE)
     */
    private static String formatRuntimeError(String stderr, String language) {
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



    private static String normalizeOutput(String text) {
        if (text == null) {
            return "";
        }
        return Arrays.stream(text.stripTrailing().split("\n", -1))
                .map(line -> line.stripTrailing())
                .collect(Collectors.joining("\n"));
    }

}
