package org.example.servicejudge.judge;

import org.example.servicejudge.Dto.JudgeReturnDto;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 判题结果格式化工具（原 {@code JudgeService} 私有静态方法迁出）。
 *
 * <p>无状态纯函数集合：统一构造 {@link JudgeReturnDto}、将容器 stderr
 * 归一化为用户可读的运行时错误信息、合并函数模式的标准/错误输出、
 * 以及比对前的输出规范化。线程安全。</p>
 */
public final class JudgeResults {

    private JudgeResults() {
    }

    /**
     * 构建判题返回 DTO。
     *
     * @param exitCode   程序退出码（超时为 124；null 表示未执行）
     * @param timeUsed   用时（毫秒）
     * @param log        原始 stderr 日志
     * @param errorMsg   面向用户的错误摘要
     * @param stdout     程序标准输出
     * @param memoryUsed 内存峰值（KB）
     * @return 已填充的 DTO
     */
    public static JudgeReturnDto formatJudgeReturnDto(
            Integer exitCode,
            Integer timeUsed,
            String log,
            String errorMsg,
            String stdout,
            Integer memoryUsed
    ) {
        return JudgeReturnDto.builder()
                .exitCode(exitCode)
                .timeUsed(timeUsed)
                .log(log)
                .errorMsg(errorMsg)
                .stdout(stdout)
                .memoryUsed(memoryUsed)
                .build();
    }

    /**
     * 格式化运行时错误（RE）：提取首个异常类/错误行，找不到则截取前 200 字符。
     *
     * @param stderr   容器内捕获的 stderr 文本
     * @param language 判题语言（当前实现未区分语言，保留参数以兼容原签名）
     * @return 用户可读的运行时错误描述
     */
    public static String formatRuntimeError(String stderr, String language) {
        if (stderr == null || stderr.isEmpty()) {
            return "程序运行时发生崩溃。";
        }

        // Java 通常是 Exception in thread "main" java.lang.XxxException
        // Python 通常是 Traceback... XxxError: ...
        for (String line : stderr.split("\n")) {
            line = line.trim();
            if (line.contains("Exception") || line.contains("Error:") || line.contains("Error:")) {
                return "运行时错误: " + line.substring(line.indexOf(":") + 1).trim();
            }
        }
        // 没找到具体的异常类，返回前 200 个字符
        return "运行时错误:\n" + stderr.substring(0, Math.min(stderr.length(), 200));
    }

    /**
     * 合并函数模式的标准输出与错误输出为一条日志。
     *
     * @param stdout 批量执行结果中的标准输出
     * @param stderr 批量执行结果中的错误输出
     * @return 合并后的日志文本；两侧皆空时返回空字符串
     */
    public static String mergeFunctionLog(String stdout, String stderr) {
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

    /**
     * 规范化输出文本：去掉每行行尾空白并保留空行结构，用于期望输出比对。
     *
     * @param text 原始输出，可为 null
     * @return 规范化后的文本；入参为 null 时返回空字符串
     */
    public static String normalizeOutput(String text) {
        if (text == null) {
            return "";
        }
        return Arrays.stream(text.stripTrailing().split("\n", -1))
                .map(line -> line.stripTrailing())
                .collect(Collectors.joining("\n"));
    }
}
