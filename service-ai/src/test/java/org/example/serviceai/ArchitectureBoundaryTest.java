package org.example.serviceai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构约束：业务层只允许依赖顶层契约 {@code AiFacade} / {@code AiRequest} / {@code AiTarget}，
 * 不得直接引用端点池、工厂、Provider、key 池、传输层与 Facade 实现。
 *
 * <p>这条约束是整个拆分的意义所在，也是最容易被下一个人无意破坏的地方——
 * 一句 {@code import ...ai.pool.AiPool} 就能把耦合重新接回去，而且编译、测试全都不会报错。
 * 所以用测试把边界钉死，而不是靠约定。</p>
 */
class ArchitectureBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/org/example/serviceai");

    private static final List<String> BUSINESS_PACKAGES = List.of(
            "conversation",
            "handle",
            "serviceQuestion",
            "MQ",
            "controller",
            "service"
    );

    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "org.example.serviceai.ai.pool",
            "org.example.serviceai.ai.provider",
            "org.example.serviceai.ai.transport",
            "org.example.serviceai.ai.key",
            "org.example.serviceai.ai.facade"
    );

    @Test
    void businessLayerDependsOnlyOnTheFacadeContract() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String businessPackage : BUSINESS_PACKAGES) {
            Path packageRoot = SOURCE_ROOT.resolve(businessPackage);
            if (!Files.isDirectory(packageRoot)) {
                continue;
            }
            violations.addAll(scanForbiddenImports(packageRoot));
        }

        assertThat(violations)
                .as("业务层只能依赖 AiFacade/AiRequest/AiTarget，不得直接引用实现层")
                .isEmpty();
    }

    @Test
    void facadeContractItselfIsReachableFromBusinessCode() {
        // 正向确认：约束不是靠「谁也 import 不到」实现的
        assertThat(Files.isRegularFile(
                SOURCE_ROOT.resolve("ai/AiFacade.java"))).isTrue();
        assertThat(Files.isRegularFile(
                SOURCE_ROOT.resolve("ai/AiRequest.java"))).isTrue();
    }

    /** 自检：扫描器对真实违规必须报出来，否则上面那条断言只是「永远为真」。 */
    @Test
    void scannerReportsViolationsWhenTheyExist(@TempDir Path tempDir) throws IOException {
        Path businessFile = tempDir.resolve("conversation/FakeService.java");
        Files.createDirectories(businessFile.getParent());
        Files.writeString(businessFile, """
                package org.example.serviceai.conversation;

                import org.example.serviceai.ai.AiFacade;
                import org.example.serviceai.ai.pool.AiPool;

                class FakeService {
                    AiFacade facade;
                    AiPool pool;
                }
                """);

        List<String> violations = scanForbiddenImports(tempDir);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("ai.pool.AiPool");
    }

    private List<String> scanForbiddenImports(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }
                    for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                        if (trimmed.startsWith("import " + forbidden)) {
                            violations.add(file + " -> " + trimmed);
                        }
                    }
                }
            }
        }
        return violations;
    }
}
