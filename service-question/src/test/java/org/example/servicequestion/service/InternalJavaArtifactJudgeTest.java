package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部 Java 函数产物验收器测试：工作区准备、沙箱脚本与结果校验为离线单测；
 * 端到端验收需要 Docker 与 codewise-java-judge:17 镜像，以 CODEWISE_DOCKER_IT=true
 * 显式开启（产物一律沙箱内执行，不再有宿主机本地执行路径）。
 */
class InternalJavaArtifactJudgeTest {

    @TempDir
    Path tempDirectory;

    private final InternalJavaArtifactJudge judge =
            new InternalJavaArtifactJudge(new ObjectMapper(), "codewise-java-judge:17", "docker", 30);

    @Test
    void prepareJudgeWorkspaceCopiesSourcesWithCanonicalNamesAndClearsStaleOutputs() throws Exception {
        Path generatorSource = tempDirectory.resolve("两数之和-generator.java");
        Path standardAnswerSource = tempDirectory.resolve("两数之和-standard-answer.java");
        Files.writeString(generatorSource, "public class Generator {}", StandardCharsets.UTF_8);
        Files.writeString(standardAnswerSource, "public class Main {}", StandardCharsets.UTF_8);
        Files.writeString(tempDirectory.resolve("inputs.jsonl"), "[1]", StandardCharsets.UTF_8);
        Files.writeString(tempDirectory.resolve("outputs.jsonl"), "1", StandardCharsets.UTF_8);
        Files.writeString(tempDirectory.resolve("judge.log"), "stale", StandardCharsets.UTF_8);

        Path judgeWorkspace = tempDirectory.resolve(".judge-work");
        judge.prepareJudgeWorkspace(tempDirectory, judgeWorkspace, generatorSource, standardAnswerSource);

        assertEquals("public class Generator {}",
                Files.readString(judgeWorkspace.resolve("Generator.java"), StandardCharsets.UTF_8));
        assertEquals("public class Main {}",
                Files.readString(judgeWorkspace.resolve("Main.java"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempDirectory.resolve("inputs.jsonl")));
        assertFalse(Files.exists(tempDirectory.resolve("outputs.jsonl")));
        assertFalse(Files.exists(tempDirectory.resolve("judge.log")));
    }

    @Test
    void buildScriptCompilesRunsGeneratorAndChecksCaseCount() {
        String script = judge.buildScript(123456L);

        assertTrue(script.contains("javac -cp \"/opt/judge/lib/*\" -encoding UTF-8 Generator.java Main.java"));
        assertTrue(script.contains("Generator 123456 50"));
        assertTrue(script.contains("Main < /workspace/inputs.jsonl"));
        assertTrue(script.contains("-eq 50"));
    }

    @Test
    void readValidatedCasesAcceptsFiftyArrayLines() throws Exception {
        writeCaseFiles(50);

        List<InternalJavaArtifactJudge.ValidatedCase> cases = judge.readValidatedCases(tempDirectory);

        assertEquals(50, cases.size());
        assertEquals(0, cases.getFirst().input().get(0).asInt());
        assertEquals(49, cases.getLast().input().get(0).asInt());
    }

    @Test
    void readValidatedCasesRejectsWrongLineCount() throws Exception {
        writeCaseFiles(49);

        assertThrows(IllegalStateException.class, () -> judge.readValidatedCases(tempDirectory));
    }

    @Test
    void readValidatedCasesRejectsNonArrayInput() throws Exception {
        writeCaseFiles(50);
        StringBuilder inputs = new StringBuilder("\"not-an-array\"\n");
        for (int index = 1; index < 50; index++) {
            inputs.append("[").append(index).append("]\n");
        }
        Files.writeString(tempDirectory.resolve("inputs.jsonl"), inputs.toString(), StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> judge.readValidatedCases(tempDirectory));
    }

    /** 端到端沙箱验收：CODEWISE_DOCKER_IT=true 且本机 Docker 可用时执行。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "CODEWISE_DOCKER_IT", matches = "true")
    void shouldJudgeReadableSourceNamesThroughDockerSandbox() throws Exception {
        Path generatorSource = tempDirectory.resolve("两数之和-generator.java");
        Path standardAnswerSource = tempDirectory.resolve("两数之和-standard-answer.java");
        Files.writeString(generatorSource, """
                public class Generator {
                    public static void main(String[] args) {
                        for (int index = 0; index < 50; index++) {
                            System.out.println("[" + index + "]");
                        }
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(standardAnswerSource, """
                import java.io.BufferedReader;
                import java.io.InputStreamReader;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                System.out.println(line.substring(1, line.length() - 1));
                            }
                        }
                    }
                }
                """, StandardCharsets.UTF_8);

        List<InternalJavaArtifactJudge.ValidatedCase> cases = judge.validate(
                tempDirectory, generatorSource, standardAnswerSource, 1L
        );

        assertEquals(50, cases.size());
        assertEquals(0, cases.getFirst().output().asInt());
        assertFalse(Files.exists(tempDirectory.resolve(".judge-work")));
    }

    private void writeCaseFiles(int count) throws Exception {
        StringBuilder inputs = new StringBuilder();
        StringBuilder outputs = new StringBuilder();
        for (int index = 0; index < count; index++) {
            inputs.append("[").append(index).append("]\n");
            outputs.append(index).append("\n");
        }
        Files.writeString(tempDirectory.resolve("inputs.jsonl"), inputs.toString(), StandardCharsets.UTF_8);
        Files.writeString(tempDirectory.resolve("outputs.jsonl"), outputs.toString(), StandardCharsets.UTF_8);
    }
}
