package org.example.serviceai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceai.ai.AiFacade;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.dto.ai.FunctionDescriptionRequest;
import org.example.serviceapi.dto.ai.FunctionDescriptionResponse;
import org.example.serviceapi.dto.ai.FunctionParameterRange;
import org.example.serviceapi.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 函数题题面重写：把调用方自有的素材（已验证解法、参考实现、作者笔记）交给模型，
 * 产出<b>原创表述</b>的题面。
 *
 * <p>两个刻意的约束：</p>
 * <ol>
 *   <li><b>不接触第三方题目原文</b>：请求里只有调用方的代码与笔记，模型无从复述他人题面，
 *       从源头降低版权风险；提示词再明确禁止出现第三方平台名与题号。</li>
 *   <li><b>走指定端点而不是 AUTO</b>：默认端点是订阅制的 OpenCode Go，它不参与 AUTO 轮转
 *       （AUTO 调用方没有会话概念，打过去会 400），这里用固定会话种子派生会话 id，
 *       让同批请求复用上游 prompt cache。</li>
 * </ol>
 */
@Service
@Slf4j
public class FunctionDescriptionService {

    /** 平台随机测试数据规模：提示词按此描述约束，保证题面与判题数据一致。 */
    private static final String DATA_SCALE = """
            - int：取值 -1000 ~ 1000
            - long：取值 -100000 ~ 100000
            - String：长度 0 ~ 20，只含大小写字母与数字
            - int[]：长度 0 ~ 20，元素取值 -100 ~ 100
            - String[]：长度 0 ~ 10，每个字符串长度 0 ~ 10
            - int[][]：行数 0 ~ 5，每行长度 0 ~ 5，元素取值 -100 ~ 100
            - 二叉树（以层序数组给出，空节点为 null）：节点数不超过 15，节点值取值 -100 ~ 100
            - 链表（以数组给出）：节点数 0 ~ 20，节点值取值 -100 ~ 100
            """;

    private final AiFacade aiFacade;
    private final ObjectMapper objectMapper;
    private final String providerIdentity;
    private final String modelName;
    private final String sessionSeed;
    private final int maxTokens;
    private final int maxDescriptionLength;

    public FunctionDescriptionService(
            AiFacade aiFacade,
            ObjectMapper objectMapper,
            @Value("${codewise.function-description.provider:opencode-go}") String providerIdentity,
            @Value("${codewise.function-description.model:}") String modelName,
            @Value("${codewise.function-description.session:codewise-function-description}") String sessionSeed,
            @Value("${codewise.function-description.max-tokens:8192}") int maxTokens,
            @Value("${codewise.function-description.max-description-length:20000}") int maxDescriptionLength
    ) {
        this.aiFacade = aiFacade;
        this.objectMapper = objectMapper;
        this.providerIdentity = providerIdentity;
        this.modelName = modelName == null || modelName.isBlank() ? null : modelName.trim();
        this.sessionSeed = sessionSeed;
        this.maxTokens = maxTokens;
        this.maxDescriptionLength = maxDescriptionLength;
    }

    public FunctionDescriptionResponse generate(FunctionDescriptionRequest request) {
        validate(request);
        String response = aiFacade.chat(
                AiRequest.provider(providerIdentity, buildPrompt(request), maxTokens)
                        .withSessionRef(sessionSeed)
                        // 模型名留空时用端点的默认模型；配置了就用指定模型（订阅制端点的可用模型由上游 key 决定）
                        .withModel(modelName)
        );
        return parse(response);
    }

    private String buildPrompt(FunctionDescriptionRequest request) {
        return """
                你是 CodeWise 题库的题目编辑，负责为平台自建题库撰写「函数题」题面。你只会拿到一份已经通过本地对拍验证的解法源码，\
                以及可选的参考实现与作者笔记；请据此还原题意并<b>用你自己的语言重新写一遍</b>。

                【素材】
                标题：%s
                提交类名：%s
                函数名：%s
                参数顺序与类型：%s
                返回值类型：%s
                难度参考（1 简单 / 2 中等 / 3 困难）：%s
                标签参考：%s

                已验证解法源码：
                ```java
                %s
                ```
                %s%s

                【硬性要求】
                1. 必须原创表述：禁止复述、翻译或改写任何既有平台（如 LeetCode/力扣、洛谷、牛客等）的题面文字、示例或描述；\
                正文中禁止出现这些平台的名字、题目编号或题目标识。
                2. 禁止编造输入输出示例：样例由平台用真实执行结果回填，题面正文里不要写「示例 1」「样例输入/输出」这类小节。
                3. 数据范围必须与平台生成的测试数据一致（不要写出更大的数量级）：
                %s
                4. 按函数题形式描述：说明需要实现哪个函数、参数含义、返回值含义与边界情况；\
                不要描述标准输入/输出格式，也不要出现 main 函数、Scanner、System.in 之类的说法。
                5. 语言为简体中文，使用 Markdown，但不要使用一级标题、不要使用表格；正文 150 ~ 800 字。
                6. 标签为 2 ~ 5 个中文短语（每个不超过 8 个字），用英文逗号分隔，描述算法或数据结构。
                7. 还要给出每个参数的<b>取值域建议</b>（parameterRanges）：你在题面里写了什么约束，就把它翻译成可执行的范围，                平台会用它生成测试数据，保证数据与题面一致。仅在该参数确实有约束（如「正整数」「数字 0~9」「长度不超过 100」）时才给，                数值参数用 min/max，长度/个数/行数/节点数用 minSize/maxSize；二维数组的列数用 innerMinSize/innerMaxSize；                字符串数组里单个串的长度用 elementMinSize/elementMaxSize。不要给出比上面「数据范围」更大的数量级。
                8. 只输出 JSON，不要 Markdown 代码块，不要任何解释文字。

                【JSON 格式】
                {"description":"题面正文","inputDesc":"输入说明","outputDesc":"输出说明","hint":"提示（可空字符串）","difficulty":1,"tags":"数组,哈希表",
                 "parameterRanges":[{"name":"depth","min":1,"max":1000},{"name":"l1","min":0,"max":9,"minSize":1,"maxSize":8}]}
                """.formatted(
                safe(request.getTitle()),
                safe(request.getClassName()),
                safe(request.getMethodName()),
                safe(request.getParameterConfig()),
                safe(request.getOutputType()),
                request.getDifficulty() == null ? "未提供" : String.valueOf(request.getDifficulty()),
                safe(request.getTags()).isBlank() ? "未提供" : request.getTags(),
                safe(request.getSolutionCode()),
                section("参考实现或对拍测试代码", request.getReferenceCode()),
                section("作者笔记", request.getNotes()),
                DATA_SCALE
        );
    }

    private String section(String label, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return "\n" + label + "：\n```\n" + content + "\n```\n";
    }

    private FunctionDescriptionResponse parse(String response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stripFence(response));
        } catch (JsonProcessingException exception) {
            throw new BizException(AiErrorCode.AI_RESULT_PARSE_FAILED, exception);
        }

        String description = root.path("description").asText("").trim();
        if (description.isEmpty()) {
            throw new BizException(AiErrorCode.DESCRIPTION_RESULT_INVALID);
        }
        if (description.length() > maxDescriptionLength) {
            log.warn("模型返回的题面过长，length={}", description.length());
            throw new BizException(AiErrorCode.DESCRIPTION_RESULT_INVALID);
        }

        FunctionDescriptionResponse result = new FunctionDescriptionResponse();
        result.setDescription(description);
        result.setInputDesc(trimmed(root, "inputDesc"));
        result.setOutputDesc(trimmed(root, "outputDesc"));
        result.setHint(trimmed(root, "hint"));
        result.setDifficulty(parseDifficulty(root.path("difficulty")));
        result.setTags(trimmed(root, "tags"));
        result.setParameterRanges(parseRanges(root.path("parameterRanges")));
        return result;
    }

    /**
     * 解析取值域建议：只收能对上名字、字段形态正确的项。
     *
     * <p>这里刻意只做「形状」校验（名字非空、值是数字），量纲是否合理交给生成器的裁剪，
     * 参数名是否存在于签名则交给 service-question 的合并逻辑——那两处各自有更完整的上下文。</p>
     */
    private List<FunctionParameterRange> parseRanges(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<FunctionParameterRange> ranges = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String name = item.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            FunctionParameterRange range = new FunctionParameterRange();
            range.setName(name);
            range.setMin(numberOrNull(item, "min"));
            range.setMax(numberOrNull(item, "max"));
            range.setMinSize(intOrNull(item, "minSize"));
            range.setMaxSize(intOrNull(item, "maxSize"));
            range.setInnerMinSize(intOrNull(item, "innerMinSize"));
            range.setInnerMaxSize(intOrNull(item, "innerMaxSize"));
            range.setElementMinSize(intOrNull(item, "elementMinSize"));
            range.setElementMaxSize(intOrNull(item, "elementMaxSize"));
            ranges.add(range);
        }
        return ranges;
    }

    private Long numberOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    /** 模型偶尔仍会带 Markdown 围栏，容错剥掉一层。 */
    private String stripFence(String response) {
        String json = response == null ? "" : response.trim();
        if (!json.startsWith("```")) {
            return json;
        }
        int firstLineEnd = json.indexOf('\n');
        int lastFence = json.lastIndexOf("```");
        if (firstLineEnd > 0 && lastFence > firstLineEnd) {
            return json.substring(firstLineEnd + 1, lastFence).trim();
        }
        return json;
    }

    /** 难度：接受 1/2/3，也接受「简单/中等/困难」；无法识别时返回 null 交给调用方兜底。 */
    private Integer parseDifficulty(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            int value = node.asInt();
            return value >= 1 && value <= 3 ? value : null;
        }
        return switch (node.asText("").trim()) {
            case "1", "简单", "Easy", "easy" -> 1;
            case "2", "中等", "Medium", "medium" -> 2;
            case "3", "困难", "Hard", "hard" -> 3;
            default -> null;
        };
    }

    private String trimmed(JsonNode root, String field) {
        return root.path(field).asText("").trim();
    }

    private void validate(FunctionDescriptionRequest request) {
        if (request == null) {
            throw new BizException(AiErrorCode.FUNCTION_SIGNATURE_INCOMPLETE);
        }
        if (safe(request.getSolutionCode()).isBlank()) {
            throw new BizException(AiErrorCode.DESCRIPTION_SOLUTION_REQUIRED);
        }
        if (safe(request.getMethodName()).isBlank() || safe(request.getOutputType()).isBlank()) {
            throw new BizException(AiErrorCode.FUNCTION_SIGNATURE_INCOMPLETE);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
