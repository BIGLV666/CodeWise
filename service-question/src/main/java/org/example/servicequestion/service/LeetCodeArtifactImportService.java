package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateRequest;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateResponse;
import org.example.serviceapi.feign.AiArtifactFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.dto.FunctionDto;
import org.example.servicequestion.dto.FunctionTestCaseDto;
import org.example.servicequestion.vo.FunctionParseVo;
import org.example.servicequestion.vo.LeetCodeArtifactTaskVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LeetCodeArtifactImportService {
    private final Map<String, LeetCodeArtifactTaskVo> tasks = new ConcurrentHashMap<>();

    private final QuestionPermissionService permissionService;
    private final FunctionQuestionParseService parseService;
    private final AiArtifactFeignClient aiArtifactFeignClient;
    private final InternalJavaArtifactJudge internalJudge;
    private final FunctionCaseDataConverter caseDataConverter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor taskExecutor;
    private final Path artifactRoot;
    private final String internalToken;

    public LeetCodeArtifactImportService(
            QuestionPermissionService permissionService,
            FunctionQuestionParseService parseService,
            AiArtifactFeignClient aiArtifactFeignClient,
            InternalJavaArtifactJudge internalJudge,
            FunctionCaseDataConverter caseDataConverter,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            @Qualifier("service-question") TaskExecutor taskExecutor,
            @Value("${codewise.function-artifact.root:./data/function-artifacts}") String artifactRoot,
            @Value("${codewise.internal-token}") String internalToken
    ) {
        this.permissionService = permissionService;
        this.parseService = parseService;
        this.aiArtifactFeignClient = aiArtifactFeignClient;
        this.internalJudge = internalJudge;
        this.caseDataConverter = caseDataConverter;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.taskExecutor = taskExecutor;
        this.artifactRoot = Path.of(artifactRoot).toAbsolutePath().normalize();
        this.internalToken = internalToken;
    }

    public LeetCodeArtifactTaskVo start(String titleSlug) {
        Long userId = UserContext.getUserId();
        permissionService.requireAdmin(userId);
        if (titleSlug == null || titleSlug.isBlank()) {
            throw new IllegalArgumentException("titleSlug 不能为空");
        }

        String taskId = UUID.randomUUID().toString();
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        Path taskDirectory = artifactRoot.resolve(taskId);
        LeetCodeArtifactTaskVo task = LeetCodeArtifactTaskVo.builder()
                .taskId(taskId)
                .titleSlug(titleSlug.trim())
                .userId(userId)
                .status("PENDING")
                .generatedCount(0)
                .seed(seed)
                .artifactDirectory(taskDirectory.toString())
                .createTime(LocalDateTime.now())
                .build();
        tasks.put(taskId, task);
        try {
            Files.createDirectories(taskDirectory);
            writeStatus(taskDirectory, task);
        } catch (IOException exception) {
            throw new IllegalStateException("生成任务目录创建失败", exception);
        }
        taskExecutor.execute(() -> execute(taskId, titleSlug.trim()));
        return task;
    }

    public LeetCodeArtifactTaskVo getTask(String taskId) {
        permissionService.requireAdmin(UserContext.getUserId());
        LeetCodeArtifactTaskVo task = tasks.get(taskId);
        if (task == null) {
            task = loadTaskFromFile(taskId);
            tasks.put(taskId, task);
        }
        return task;
    }

    public void execute(String taskId, String titleSlug) {
        LeetCodeArtifactTaskVo task = tasks.get(taskId);
        Path taskDirectory = Path.of(task.getArtifactDirectory());
        String artifactBaseName = safeArtifactBaseName(titleSlug);
        Path generatorSource = taskDirectory.resolve(artifactBaseName + "-generator.java");
        Path standardAnswerSource = taskDirectory.resolve(artifactBaseName + "-standard-answer.java");
        try {
            task.setStatus("FETCHING");
            Files.createDirectories(taskDirectory);
            FunctionParseVo parsed = parseService.fromLeetCode(titleSlug);
            artifactBaseName = safeArtifactBaseName(parsed.getTitle() == null ? titleSlug : parsed.getTitle());
            generatorSource = taskDirectory.resolve(artifactBaseName + "-generator.java");
            standardAnswerSource = taskDirectory.resolve(artifactBaseName + "-standard-answer.java");
            Files.writeString(
                    taskDirectory.resolve("question.json"),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed),
                    StandardCharsets.UTF_8
            );

            task.setStatus("GENERATING");
            FunctionArtifactGenerateResponse artifacts = generateArtifacts(parsed);
            Files.writeString(generatorSource, artifacts.getGeneratorCode(), StandardCharsets.UTF_8);
            Files.writeString(standardAnswerSource, artifacts.getStandardAnswerCode(), StandardCharsets.UTF_8);
            writeArtifactManifest(taskDirectory, generatorSource, standardAnswerSource, task.getSeed(), "not-started");

            task.setStatus("VALIDATING");
            List<InternalJavaArtifactJudge.ValidatedCase> validatedCases = internalJudge.validate(
                    taskDirectory,
                    generatorSource,
                    standardAnswerSource,
                    task.getSeed()
            );
            List<FunctionTestCaseDto> testCases = toTestCases(parsed, validatedCases);
            writeGeneratedCases(taskDirectory, testCases);

            task.setStatus("SAVING");
            Long questionId = transactionTemplate.execute(status -> {
                FunctionDto functionDto = new FunctionDto();
                BeanUtils.copyProperties(parsed, functionDto);
                functionDto.setCreateUserId(task.getUserId());
                functionDto.setSamples(parsed.getSamples());
                Long insertedQuestionId = parseService.insert(functionDto);
                parseService.insertTestCases(insertedQuestionId, testCases);
                return insertedQuestionId;
            });

            task.setQuestionId(questionId);
            task.setGeneratedCount(testCases.size());
            task.setStatus("SUCCESS");
            task.setFinishTime(LocalDateTime.now());
            writeStatus(taskDirectory, task);
        } catch (Exception exception) {
            task.setStatus("MANUAL_REVIEW");
            task.setErrorMessage(rootMessage(exception));
            task.setFinishTime(LocalDateTime.now());
            try {
                Files.createDirectories(taskDirectory);
                Files.writeString(taskDirectory.resolve("error.txt"), stackSummary(exception), StandardCharsets.UTF_8);
                writeArtifactManifest(taskDirectory, generatorSource, standardAnswerSource, task.getSeed(), "failed");
                writeStatus(taskDirectory, task);
            } catch (IOException ignored) {
            }
        }
    }

    private FunctionArtifactGenerateResponse generateArtifacts(FunctionParseVo parsed) {
        FunctionArtifactGenerateRequest request = new FunctionArtifactGenerateRequest();
        BeanUtils.copyProperties(parsed, request);
        request.setSampleInput(parsed.getSamples() == null || parsed.getSamples().isEmpty()
                ? "" : parsed.getSamples().getFirst().getInput());
        request.setSampleOutput(parsed.getSamples() == null || parsed.getSamples().isEmpty()
                ? "" : parsed.getSamples().getFirst().getOutput());
        Result<FunctionArtifactGenerateResponse> response = aiArtifactFeignClient.generateFunctionArtifacts(
                internalToken,
                request
        );
        if (response == null || response.getCode() != 200 || response.getData() == null) {
            throw new IllegalStateException(response == null ? "AI 服务无响应" : response.getMessage());
        }
        return response.getData();
    }

    private List<FunctionTestCaseDto> toTestCases(
            FunctionParseVo parsed,
            List<InternalJavaArtifactJudge.ValidatedCase> validatedCases
    ) {
        JsonNode parameters = readJson(parsed.getParameterConfig());
        List<FunctionTestCaseDto> result = new ArrayList<>(validatedCases.size());
        for (InternalJavaArtifactJudge.ValidatedCase validatedCase : validatedCases) {
            if (validatedCase.input().size() != parameters.size()) {
                throw new IllegalStateException("AI 生成的输入参数数量与函数签名不一致");
            }
            List<String> values = new ArrayList<>(parameters.size());
            for (int index = 0; index < parameters.size(); index++) {
                JsonNode value = validatedCase.input().get(index);
                String type = parameters.get(index).path("type").asText();
                values.add(isStringType(type) && value.isTextual()
                        ? value.asText()
                        : value.toString());
            }
            String input = caseDataConverter.normalizeInput(
                    String.join("\n", values),
                    parsed.getParameterConfig()
            );
            String rawOutput = validatedCase.output().isTextual()
                    ? validatedCase.output().asText()
                    : validatedCase.output().toString();
            String output = caseDataConverter.normalizeOutput(rawOutput, parsed.getOutputType());
            FunctionTestCaseDto testCase = new FunctionTestCaseDto();
            testCase.setInput(input);
            testCase.setOutput(output);
            result.add(testCase);
        }
        return result;
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException exception) {
            throw new IllegalArgumentException("函数参数配置格式错误", exception);
        }
    }

    private void writeGeneratedCases(
            Path directory,
            List<FunctionTestCaseDto> testCases
    ) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("# CodeWise generated function test cases\n")
                .append("# count: ").append(testCases.size()).append("\n")
                .append("# format: input parameters are separated by newline\n\n");

        for (int index = 0; index < testCases.size(); index++) {
            FunctionTestCaseDto testCase = testCases.get(index);
            text.append("===== CASE ").append(index + 1).append(" =====\n")
                    .append("INPUT\n")
                    .append(testCase.getInput()).append("\n")
                    .append("OUTPUT\n")
                    .append(testCase.getOutput()).append("\n\n");
        }
        Files.writeString(
                directory.resolve("generated-cases.txt"),
                text,
                StandardCharsets.UTF_8
        );
    }

    private boolean isStringType(String type) {
        return "String".equals(type) || "char".equals(type) || "Character".equals(type);
    }

    static String safeArtifactBaseName(String title) {
        String value = title == null ? "" : title.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[. -]+|[. -]+$", "");
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
            value = "problem";
        }
        if (value.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")) {
            value += "-problem";
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private void writeArtifactManifest(
            Path taskDirectory,
            Path generatorSource,
            Path standardAnswerSource,
            long seed,
            String judgeStatus
    ) throws IOException {
        Files.writeString(
                taskDirectory.resolve("artifacts.txt"),
                "artifactDirectory=" + taskDirectory + "\n"
                        + "generator=" + generatorSource + "\n"
                        + "standardAnswer=" + standardAnswerSource + "\n"
                        + "seed=" + seed + "\n"
                        + "judge=" + judgeStatus + "\n",
                StandardCharsets.UTF_8
        );
    }

    private void writeStatus(Path directory, LeetCodeArtifactTaskVo task) throws IOException {
        Files.writeString(
                directory.resolve("status.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(task),
                StandardCharsets.UTF_8
        );
    }

    private LeetCodeArtifactTaskVo loadTaskFromFile(String taskId) {
        if (taskId == null || !taskId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("任务 ID 格式错误");
        }
        Path statusPath = artifactRoot.resolve(taskId).normalize().resolve("status.json");
        if (!statusPath.startsWith(artifactRoot) || !Files.isRegularFile(statusPath)) {
            throw new IllegalArgumentException("任务不存在");
        }
        try {
            return objectMapper.readValue(statusPath.toFile(), LeetCodeArtifactTaskVo.class);
        } catch (IOException exception) {
            throw new IllegalStateException("任务状态读取失败", exception);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String stackSummary(Throwable throwable) {
        return throwable.getClass().getName() + ": " + rootMessage(throwable);
    }
}
