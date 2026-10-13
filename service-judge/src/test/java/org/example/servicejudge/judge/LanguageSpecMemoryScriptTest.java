package org.example.servicejudge.judge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 判题脚本文案回归测试：内存指标依赖脚本内的 GNU time 包装，脚本是字符串模板，
 * 改动极易静默失效，故用断言把契约锁住（外部进程行为由线上探针验证）。
 */
class LanguageSpecMemoryScriptTest {

    @Test
    void acmRunScriptsShouldReportExitCodeAndPeakMemory() {
        for (LanguageSpec spec : LanguageSpec.values()) {
            String script = spec.buildRunScript();
            assertAll(
                    () -> assertTrue(script.contains("command -v /usr/bin/time"),
                            spec.language() + ": 缺少 GNU time 存在性判断"),
                    () -> assertTrue(script.contains("/usr/bin/time -f __PEAK_KB__%M"),
                            spec.language() + ": 缺少 GNU time 峰值格式（模板里应写 %%M）"),
                    () -> assertTrue(script.contains("-o /workspace/memory-kb.txt"),
                            spec.language() + ": 峰值未落到容器内文件"),
                    () -> assertTrue(script.contains("timeout 2s "),
                            spec.language() + ": 单用例时限应为 2 秒且保持在 timeout 内层"),
                    () -> assertTrue(script.contains("echo $code > exitcode.txt"),
                            spec.language() + ": exitcode.txt 首行应为退出码"),
                    () -> assertTrue(script.contains("echo \"$mem_kb\" >> exitcode.txt"),
                            spec.language() + ": exitcode.txt 次行应为峰值内存"),
                    () -> assertTrue(script.contains("exit $code"),
                            spec.language() + ": 脚本退出码应保持被测程序退出码"),
                    () -> assertFalse(script.contains("[[") || script.contains("#!/bin/bash"),
                            spec.language() + ": 脚本必须保持 POSIX sh 兼容（容器内用 /bin/sh 执行）")
            );
        }
    }

    @Test
    void pythonAndNativeScriptsShouldKeepTheirRunCommand() {
        assertTrue(LanguageSpec.PYTHON.buildRunScript().contains("timeout 2s python3 solution.py"));
        assertTrue(LanguageSpec.CPP.buildRunScript().contains("timeout 2s ./solution"));
        assertTrue(LanguageSpec.C.buildRunScript().contains("timeout 2s ./solution"));
        assertTrue(LanguageSpec.JAVA.buildRunScript().contains("timeout 2s java "));
    }

    @Test
    void batchRunScriptShouldAppendPeakMemoryMarker() {
        String script = LanguageSpec.FUNCTION_JAVA_BATCH_RUN_SCRIPT;

        assertAll(
                () -> assertTrue(script.contains("command -v /usr/bin/time"),
                        "缺少 GNU time 存在性判断"),
                () -> assertTrue(script.contains("/usr/bin/time -f __PEAK_KB__%M"),
                        "批量脚本不经 String.format，%M 应按字面写"),
                () -> assertTrue(script.contains("-o /workspace/memory-kb.txt")),
                () -> assertTrue(script.contains("printf '__CODEWISE_MEM_KB__%s\\n' \"$mem_kb\" >> batch-result.txt"),
                        "峰值应作为标记追加进 batch-result.txt（避免额外的 Docker 调用）"),
                () -> assertTrue(script.contains("-Dcodewise.timeLimitMs=2000"),
                        "时间上限占位应已被替换"),
                () -> assertFalse(script.contains("__TIME_LIMIT_MS__"), "占位符不应残留"),
                () -> assertTrue(script.contains("timeout \"${process_timeout}s\" java"),
                        "批量 JVM 仍应由 timeout 内层包住")
        );
    }
}
