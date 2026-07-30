package org.example.serviceai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateRequest;
import org.example.serviceapi.dto.ai.FunctionArtifactGenerateResponse;
import org.springframework.stereotype.Service;

@Service
public class FunctionArtifactService {
    private static final String CONTRACT_VERSION = "function-artifact-v1";

    private final AIService aiService;
    private final ObjectMapper objectMapper;

    public FunctionArtifactService(AIService aiService, ObjectMapper objectMapper) {
        this.aiService = aiService;
        this.objectMapper = objectMapper;
    }

    public FunctionArtifactGenerateResponse generate(FunctionArtifactGenerateRequest request) {
        validate(request);
        String response = aiService.callAi(buildPrompt(request), 8192);
        JsonNode root = parseJson(response);

        FunctionArtifactGenerateResponse result = new FunctionArtifactGenerateResponse();
        result.setGeneratorCode(requiredText(root, "generatorCode"));
        result.setStandardAnswerCode(requiredText(root, "standardAnswerCode"));
        result.setContractVersion(CONTRACT_VERSION);
        validateCode(result);
        return result;
    }

    private String buildPrompt(FunctionArtifactGenerateRequest request) {
        return """
                你是 CodeWise 的函数题对拍数据生成器工程师。你只负责生成两份 Java 17 源码，严禁直接生成或枚举测试样例。直接返回纯 JSON，不要 Markdown 代码块。

                【题目信息】
                标题：%s
                描述：%s
                输入说明：%s
                输出说明：%s
                样例输入：%s
                样例输出：%s
                函数类名：%s
                方法名：%s
                参数配置：%s
                返回类型：%s

                【源码契约】
                1. generatorCode 必须是 public class Generator，只能包含随机输入生成逻辑，不能包含每组测试的期望输出。
                2. Generator.main 必须读取 args[0] 作为 long seed，读取 args[1] 作为 int count，并使用 java.util.Random 或 SplittableRandom。
                3. Generator 必须根据 seed 可重复生成 count 组随机输入，不得硬编码固定的50组输入。
                4. Generator 每组输入打印一行合法 JSON 数组，数组元素依次对应参数配置中的参数；不要打印日志或解释。
                5. standardAnswerCode 必须是 public class Main，可以用 Jackson 读取标准输入的 JSON 数组。
                6. Main 每读取一行就执行一次独立的标准算法，并向标准输出打印一行合法 JSON 返回值。
                7. Main 必须处理全部输入；输出行数必须与输入行数一致。
                8. 不要依赖网络、文件或环境变量；允许使用 Java 标准库和 Jackson。
                9. 随机生成逻辑应按题目约束混合普通、边界、重复、空值、极值和特殊结构数据。
                10. 标准答案必须是真正正确的参考实现，不要生成占位代码。
                11.随机输入生成中最后必须包含至少2组极端样例生成逻辑

                【JSON格式】
                {"generatorCode":"...","standardAnswerCode":"..."}
                """.formatted(
                safe(request.getTitle()), safe(request.getDescription()), safe(request.getInputDesc()),
                safe(request.getOutputDesc()), safe(request.getSampleInput()), safe(request.getSampleOutput()),
                safe(request.getClassName()), safe(request.getMethodName()), safe(request.getParameterConfig()),
                safe(request.getOutputType())
        );
    }

    private JsonNode parseJson(String response) {
        try {
            String json = response == null ? "" : response.trim();
            if (json.startsWith("```")) {
                int firstLineEnd = json.indexOf('\n');
                int lastFence = json.lastIndexOf("```");
                if (firstLineEnd > 0 && lastFence > firstLineEnd) {
                    json = json.substring(firstLineEnd + 1, lastFence).trim();
                }
            }
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI 返回的源码结果不是合法 JSON", exception);
        }
    }

    private String requiredText(JsonNode root, String field) {
        String value = root.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("AI 返回结果缺少 " + field);
        }
        return value;
    }

    private void validate(FunctionArtifactGenerateRequest request) {
        if (request == null || safe(request.getDescription()).isBlank()) {
            throw new IllegalArgumentException("题目描述不能为空");
        }
        if (safe(request.getParameterConfig()).isBlank()
                || safe(request.getMethodName()).isBlank()
                || safe(request.getOutputType()).isBlank()) {
            throw new IllegalArgumentException("函数签名信息不完整");
        }
    }

    private void validateCode(FunctionArtifactGenerateResponse result) {
        if (!result.getGeneratorCode().contains("class Generator")
                || !result.getStandardAnswerCode().contains("class Main")) {
            throw new IllegalStateException("AI 返回的源码不符合执行契约");
        }
        if (!(result.getGeneratorCode().contains("Random")
                || result.getGeneratorCode().contains("SplittableRandom"))) {
            throw new IllegalStateException("AI 返回的 Generator 未使用随机数生成器");
        }
        if (!result.getGeneratorCode().contains("args[0]")
                || !result.getGeneratorCode().contains("args[1]")) {
            throw new IllegalStateException("AI 返回的 Generator 未接收 seed 和 count 参数");
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
