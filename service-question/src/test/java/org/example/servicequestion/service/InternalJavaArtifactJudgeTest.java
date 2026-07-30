package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InternalJavaArtifactJudgeTest {

    @TempDir
    Path tempDirectory;

    @Test
    void shouldJudgeReadableSourceNamesThroughCanonicalWorkspace() throws Exception {
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

        InternalJavaArtifactJudge judge = new InternalJavaArtifactJudge(
                new ObjectMapper(), "unused", "unused", "local", 10
        );
        List<InternalJavaArtifactJudge.ValidatedCase> cases = judge.validate(
                tempDirectory, generatorSource, standardAnswerSource, 1L
        );

        assertEquals(50, cases.size());
        assertEquals(0, cases.getFirst().output().asInt());
        assertFalse(Files.exists(tempDirectory.resolve(".judge-work")));
    }
}
