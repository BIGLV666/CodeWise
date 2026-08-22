package org.example.servicejudge.judge;

import java.util.Arrays;
import java.util.Locale;

/**
 * 判题语言规格与执行时限常量。
 *
 * <p>从 {@code JudgeService} 原私有枚举原样迁出：每种语言对应源文件名、
 * Docker 镜像、编译脚本与运行脚本；脚本内的 {@code timeout} 秒数由
 * {@link #TIME_LIMIT_MS} 统一推导，保证单用例时限与批量脚本时限一致。</p>
 *
 * <p>同文件还承载判题执行相关的时限常量与函数模式（Java）专用脚本，
 * 供 {@code JudgeService} 门面与容器执行模板共用。</p>
 */
public enum LanguageSpec {

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
            """),

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
            """),

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
            """),

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
            """);

    // ========== 执行时限常量（原 JudgeService 迁出；枚举常量必须最先声明，故置于常量列表之后） ==========

    /** 单用例运行时限（毫秒），与容器内 timeout 命令保持一致。 */
    public static final long TIME_LIMIT_MS = 2000;

    /** 编译超时（毫秒），仅用于 compile.sh 的 exec 等待上限。 */
    public static final long COMPILE_TIMEOUT_MS = 15_000L;

    /** 单用例执行等待的额外开销（毫秒），批量脚本时限按用例数叠加。 */
    public static final long EXEC_OVERHEAD_MS = 2_000L;

    /** 批量脚本 JVM 启动等固定开销（毫秒）。 */
    public static final long BATCH_STARTUP_TIMEOUT_MS = 7_000L;

    private final String language;
    private final String sourceFile;
    private final String image;
    private final String compileScript;
    /** 运行脚本模板，{@code %d} 为 timeout 秒数占位。 */
    private final String runScriptTemplate;

    LanguageSpec(String language, String sourceFile, String image, String compileScript, String runScriptTemplate) {
        this.language = language;
        this.sourceFile = sourceFile;
        this.image = image;
        this.compileScript = compileScript;
        this.runScriptTemplate = runScriptTemplate;
    }

    /** 语言标识（同时作为容器池的 key），如 java/python/cpp/c。 */
    public String language() {
        return language;
    }

    /** 该语言的判题 Docker 镜像名。 */
    public String image() {
        return image;
    }

    /** 该语言的源代码文件名（写入容器工作区用）。 */
    public String sourceFile() {
        return sourceFile;
    }

    /** 编译脚本内容。 */
    public String buildCompileScript() {
        return compileScript;
    }

    /** 运行脚本内容（按 {@link #TIME_LIMIT_MS} 推导 timeout 秒数后填充模板）。 */
    public String buildRunScript() {
        return runScriptTemplate.formatted(TIME_LIMIT_MS / 1000);
    }

    /**
     * 按语言名解析规格（大小写不敏感）。
     *
     * @param language 语言标识，可为 null
     * @return 匹配的规格；语言为空或不支持时返回 null
     */
    public static LanguageSpec of(String language) {
        if (language == null) {
            return null;
        }
        String key = language.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(spec -> spec.language.equals(key))
                .findFirst()
                .orElse(null);
    }

    // ========== 函数模式（Java）专用脚本（原 JudgeService 迁出） ==========

    /** 函数模式 JAVA 编译脚本。 */
    public static final String FUNCTION_JAVA_COMPILE_SCRIPT = """
        #!/bin/sh
        cd /workspace

        javac -cp "/opt/judge/lib/*" -encoding UTF-8 Main.java Solution.java 2> stderr.txt

        if [ $? -ne 0 ]; then
            echo 2 > exitcode.txt
            exit 2
        fi

        echo 0 > exitcode.txt
        """;

    /** 函数模式 JAVA 单用例运行脚本。 */
    public static final String FUNCTION_JAVA_RUN_SCRIPT = """
        #!/bin/sh
        cd /workspace

        rm -f result.txt
        timeout %ds java -cp "/workspace:/opt/judge/lib/*" -Xmx256m Main < input.txt > stdout.txt 2> stderr.txt

        code=$?
        echo $code > exitcode.txt
        exit $code
        """.formatted(TIME_LIMIT_MS / 1000);

    /** 函数模式 JAVA 批量运行脚本：一个 JVM 顺序跑完所有用例并输出分节标记。 */
    public static final String FUNCTION_JAVA_BATCH_RUN_SCRIPT = """
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
            printf '__CODEWISE_STDERR_BEGIN__\\n\\n' >> batch-result.txt
            if [ -s batch-process-stderr.txt ]; then cat batch-process-stderr.txt >> batch-result.txt; fi
            printf '\\n__CODEWISE_CASE_END__\\n' >> batch-result.txt
        fi
        exit "$code"
        """.replace("__TIME_LIMIT_MS__", String.valueOf(TIME_LIMIT_MS));
}
