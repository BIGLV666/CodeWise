package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.servicequestion.dto.FunctionDto;
import org.example.servicequestion.dto.FunctionTestCaseDto;
import org.example.servicequestion.entry.FunctionConfig;
import org.example.servicequestion.entry.FunctionTestCase;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.enums.QuestionType;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.mapper.FunctionTestCaseMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.vo.FunctionParseVo;
import org.example.servicequestion.vo.FunctionSampleVo;
import org.example.servicecommon.until.UserContext;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Slf4j
public class FunctionQuestionParseService {

    private static final URI LEETCODE_GRAPHQL = URI.create("https://leetcode.cn/graphql");
    private static final Pattern JAVA_CLASS_PATTERN = Pattern.compile(
            "\\bclass\\s+(\\w+)"
    );
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private)\\s+)?"
                    + "(?:(?:static|final|synchronized|native|abstract)\\s+)*"
                    + "([\\w<>\\[\\], ?]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*\\{"
    );

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuestionMapper questionMapper;
    private final FunctionConfigMapper functionConfigMapper;
    private final FunctionTestCaseMapper functionTestCaseMapper;
    private final FunctionCaseDataConverter caseDataConverter;

    public FunctionQuestionParseService(
            QuestionMapper questionMapper,
            FunctionConfigMapper functionConfigMapper,
            FunctionTestCaseMapper functionTestCaseMapper,
            FunctionCaseDataConverter caseDataConverter
    ) {
        this.questionMapper = questionMapper;
        this.functionConfigMapper = functionConfigMapper;
        this.functionTestCaseMapper = functionTestCaseMapper;
        this.caseDataConverter = caseDataConverter;
    }

    @Transactional
    public Long insert(FunctionDto functionDto) {
        if(functionDto.getDescription()==null) {
            throw new IllegalArgumentException("题目详情不能为空");
        }
        if(functionDto.getTitle()==null) {
            throw new IllegalArgumentException("标题不能为空");
        }
        Question question = new Question();
        question.setDescription(functionDto.getDescription());
        question.setTitle(functionDto.getTitle());
        question.setInputDesc(functionDto.getInputDesc()==null?"":functionDto.getInputDesc());
        question.setOutputDesc(functionDto.getOutputDesc()==null?"":functionDto.getOutputDesc());
        question.setMemoryLimit(functionDto.getMemoryLimit()==null?256:functionDto.getMemoryLimit());
        question.setTimeLimit(functionDto.getTimeLimit()==null?2000:functionDto.getTimeLimit());
        question.setHint(functionDto.getHint());
        question.setSource(functionDto.getSource());
        question.setDifficulty(functionDto.getDifficulty()==null?1:functionDto.getDifficulty());
        question.setTags(functionDto.getTags());
        question.setQuestionType(QuestionType.FUNCTION);
        question.setStatus(1);
        question.setAiStatue("success");
        question.setCreateUserId(
                functionDto.getCreateUserId() == null
                        ? UserContext.getUserId()
                        : functionDto.getCreateUserId()
        );
        question.setCreateTime(LocalDateTime.now());
        question.setUpdateTime(LocalDateTime.now());
        question.setTotalSubmit(0L);
        question.setTotalAc(0L);
        question.setPassRate(BigDecimal.ZERO);

        question.setContentHash(DigestUtils.md5DigestAsHex(question.getDescription().getBytes()));
        List<FunctionSampleVo> samples = normalizeSamples(
                functionDto.getSamples(),
                functionDto.getParameterConfig(),
                functionDto.getOutputType()
        );
        if (samples != null && !samples.isEmpty()) {
            question.setSampleInput(samples.get(0).getInput());
            question.setSampleOutput(samples.get(0).getOutput());
        }
        question.setContentHash(DigestUtils.md5DigestAsHex(
                functionDto.getDescription().getBytes(StandardCharsets.UTF_8)
        ));
        try {
            if (questionMapper.insert(question) != 1) {
                throw new IllegalStateException("函数题目保存失败");
            }
        }catch (DuplicateKeyException e){
            throw new IllegalArgumentException("该题目已存在");
        }


        FunctionConfig config = FunctionConfig.builder()
                .questionId(question.getQuestionId())
                .className(functionDto.getClassName())
                .methodName(functionDto.getMethodName())
                .parameterConfig(functionDto.getParameterConfig())
                .outputType(functionDto.getOutputType())
                .createTime(LocalDateTime.now())
                .build();
        if (functionConfigMapper.insert(config) != 1) {
            throw new IllegalStateException("函数配置保存失败");
        }

        if (samples != null) {
            for (int index = 0; index < samples.size(); index++) {
                FunctionSampleVo sample = samples.get(index);
                FunctionTestCase testCase = FunctionTestCase.builder()
                        .questionId(question.getQuestionId())
                        .inputData(sample.getInput())
                        .expectedOutput(sample.getOutput())
                        .isSample(1)
                        .isHidden(0)
                        .sortOrder(index + 1)
                        .scoreWeight(0)
                        .timeLimit(question.getTimeLimit())
                        .memoryLimit(question.getMemoryLimit())
                        .createTime(LocalDateTime.now())
                        .updateTime(LocalDateTime.now())
                        .build();
                if (functionTestCaseMapper.insert(testCase) ==0) {
                    throw new IllegalStateException("函数样例保存失败");
                }
            }
        }
        return question.getQuestionId();


    }

    @Transactional
    public int insertTestCases(Long questionId, List<FunctionTestCaseDto> testCases) {
        if (questionId == null) {
            throw new IllegalArgumentException("题目 ID 不能为空");
        }
        if (testCases == null || testCases.isEmpty()) {
            throw new IllegalArgumentException("测试用例不能为空");
        }

        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            throw new IllegalArgumentException("题目不存在");
        }
        if (question.getQuestionType() != QuestionType.FUNCTION) {
            throw new IllegalArgumentException("该题目不是函数模式");
        }

        FunctionConfig functionConfig = functionConfigMapper.selectOne(
                new LambdaQueryWrapper<FunctionConfig>()
                        .eq(FunctionConfig::getQuestionId, questionId)
                        .last("LIMIT 1")
        );
        if (functionConfig == null) {
            throw new IllegalStateException("函数配置不存在");
        }
        FunctionTestCase lastTestCase = functionTestCaseMapper.selectOne(
                new LambdaQueryWrapper<FunctionTestCase>()
                        .eq(FunctionTestCase::getQuestionId, questionId)
                        .orderByDesc(FunctionTestCase::getSortOrder)
                        .last("LIMIT 1")
        );
        int nextSortOrder = lastTestCase == null || lastTestCase.getSortOrder() == null
                ? 1
                : lastTestCase.getSortOrder() + 1;
        LocalDateTime now = LocalDateTime.now();
        List<FunctionTestCase> entities = new ArrayList<>(testCases.size());

        for (FunctionTestCaseDto testCaseDto : testCases) {
            if (testCaseDto == null) {
                throw new IllegalArgumentException("测试用例不能为空");
            }
            FunctionSampleVo normalizedCase = caseDataConverter.normalize(
                    new FunctionSampleVo(testCaseDto.getInput(), testCaseDto.getOutput()),
                    functionConfig.getParameterConfig(),
                    functionConfig.getOutputType()
            );
            FunctionTestCase entity = FunctionTestCase.builder()
                    .questionId(questionId)
                    .inputData(normalizedCase.getInput())
                    .expectedOutput(normalizedCase.getOutput())
                    .isSample(0)
                    .isHidden(1)
                    .sortOrder(nextSortOrder++)
                    .scoreWeight(100)
                    .timeLimit(question.getTimeLimit())
                    .memoryLimit(question.getMemoryLimit())
                    .createTime(now)
                    .updateTime(now)
                    .build();
            entities.add(entity);
        }
        try {
            functionTestCaseMapper.insert(entities);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("测试用例已存在", exception);
        }
        return entities.size();
    }


    public FunctionParseVo fromLeetCode(String titleSlug)
            throws IOException, InterruptedException {
        if (titleSlug == null || titleSlug.isBlank()) {
            throw new IllegalArgumentException("titleSlug 不能为空");
        }

        String normalizedSlug = normalizeTitleSlug(titleSlug);
        log.info("开始解析 LeetCode 题目, input={}, titleSlug={}", titleSlug, normalizedSlug);

        String requestBody = objectMapper.writeValueAsString(new GraphQlRequest(
                """
                query questionData($titleSlug: String!) {
                  question(titleSlug: $titleSlug) {
                    questionFrontendId
                    title
                    titleSlug
                    translatedTitle
                    content
                    translatedContent
                    difficulty
                    exampleTestcaseList
                    topicTags {
                      name
                      translatedName
                      slug
                    }
                    codeSnippets {
                      lang
                      langSlug
                      code
                    }
                  }
                }
                """,
                new Variables(normalizedSlug)
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(LEETCODE_GRAPHQL)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody,
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() != 200) {
            log.warn("LeetCode GraphQL 请求失败, titleSlug={}, status={}", normalizedSlug, response.statusCode());
            throw new IOException("LeetCode 请求失败，HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (root.hasNonNull("errors")) {
            throw new IOException("LeetCode GraphQL 返回错误: " + root.get("errors"));
        }
        JsonNode question = root.path("data").path("question");
        if (question.isMissingNode() || question.isNull()) {
            log.warn("LeetCode 未找到题目, titleSlug={}, response={}", normalizedSlug, response.body());
            throw new IllegalArgumentException("未找到题目: " + normalizedSlug);
        }
        log.info("LeetCode 题目解析成功, titleSlug={}, title={}", normalizedSlug, text(question, "title"));
        return parseQuestion(question);
    }

    private String normalizeTitleSlug(String input) {
        String value = input.trim();
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() != null && uri.getHost() != null) {
                value = uri.getPath();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("LeetCode 链接格式错误", exception);
        }

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LeetCode 题目地址不能为空");
        }
        value = value.replaceAll("/+$", "");
        int separator = value.lastIndexOf('/');
        if (separator >= 0) {
            value = value.substring(separator + 1);
        }
        value = value.trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9-]*")) {
            throw new IllegalArgumentException("无法从 LeetCode 地址中解析 titleSlug");
        }
        return value;
    }

    private FunctionParseVo parseQuestion(JsonNode question) {
        FunctionParseVo result = new FunctionParseVo();
        result.setTitle(firstNonBlank(
                text(question, "translatedTitle"),
                text(question, "title")
        ));
        result.setDescription(htmlToText(firstNonBlank(
                text(question, "translatedContent"),
                text(question, "content")
        )));
        result.setInputDesc("函数参数见 parameterConfig");
        result.setOutputDesc("返回值类型见 outputType");
        result.setHint("");
        result.setSource("LeetCode " + text(question, "questionFrontendId"));
        result.setDifficulty(parseDifficulty(text(question, "difficulty")));
        result.setTags(parseTags(question.path("topicTags")));
        result.setTimeLimit(2000);
        result.setMemoryLimit(256);
        String javaCode = findJavaSnippet(question.path("codeSnippets"));
        parseJavaSignature(javaCode, result);
        result.setSamples(parseSamples(
                firstNonBlank(text(question, "translatedContent"), text(question, "content")),
                question.path("exampleTestcaseList"),
                result.getParameterConfig(),
                result.getOutputType()
        ));
        return result;
    }

    private String findJavaSnippet(JsonNode snippets) {
        if (!snippets.isArray()) {
            throw new IllegalArgumentException("题目没有代码模板");
        }
        for (JsonNode snippet : snippets) {
            String langSlug = text(snippet, "langSlug");
            String lang = text(snippet, "lang");
            if ("java".equalsIgnoreCase(langSlug)
                    || "java".equalsIgnoreCase(lang)) {
                return text(snippet, "code");
            }
        }
        throw new IllegalArgumentException("题目没有 Java 代码模板");
    }

    private String parseTags(JsonNode topicTags) {
        if (!topicTags.isArray()) {
            return "";
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : topicTags) {
            String name = firstNonBlank(
                    text(tag, "translatedName"),
                    text(tag, "name")
            );
            if (name != null && !name.isBlank()) {
                tags.add(name);
            }
        }
        return String.join(",", tags);
    }

    private List<FunctionSampleVo> parseSamples(
            String html,
            JsonNode exampleInputs,
            String parameterConfig,
            String outputType
    ) {
        List<String> outputs = parseExampleOutputs(html);
        List<FunctionSampleVo> samples = new ArrayList<>();
        if (!exampleInputs.isArray()) {
            return samples;
        }
        for (int index = 0; index < exampleInputs.size(); index++) {
            String input = exampleInputs.get(index).asText().trim();
            String output = index < outputs.size() ? outputs.get(index) : "";
            samples.add(caseDataConverter.normalize(
                    new FunctionSampleVo(input, output),
                    parameterConfig,
                    outputType
            ));
        }
        return samples;
    }

    private List<FunctionSampleVo> normalizeSamples(
            List<FunctionSampleVo> samples,
            String parameterConfig,
            String outputType
    ) {
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        return samples.stream()
                .map(sample -> caseDataConverter.normalize(
                        sample,
                        parameterConfig,
                        outputType
                ))
                .toList();
    }

    void parseJavaSignature(String code, FunctionParseVo result) {
        Matcher classMatcher = JAVA_CLASS_PATTERN.matcher(code);
        result.setClassName(classMatcher.find() ? classMatcher.group(1) : "Solution");

        Matcher methodMatcher = JAVA_METHOD_PATTERN.matcher(code);
        if (!methodMatcher.find()) {
            throw new IllegalArgumentException("无法解析 Java 函数签名");
        }
        result.setOutputType(methodMatcher.group(1).trim());
        result.setMethodName(methodMatcher.group(2).trim());
        result.setParameterConfig(parseParameters(methodMatcher.group(3)));
    }

    List<String> parseExampleOutputs(String html) {
        List<String> outputs = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return outputs;
        }
        Pattern outputPattern = Pattern.compile(
                "(?:输出|Output)\\s*[:：]\\s*([^\\r\\n]+)",
                Pattern.CASE_INSENSITIVE
        );
        for (Element exampleBlock : Jsoup.parse(html).select(".example-block")) {
            for (Element paragraph : exampleBlock.select("p")) {
                Matcher matcher = outputPattern.matcher(paragraph.wholeText());
                if (matcher.find()) {
                    outputs.add(matcher.group(1).trim());
                    break;
                }
            }
        }
        if (!outputs.isEmpty()) {
            return outputs;
        }
        for (Element pre : Jsoup.parse(html).select("pre")) {
            Matcher matcher = outputPattern.matcher(pre.wholeText());
            if (matcher.find()) {
                outputs.add(matcher.group(1).trim());
            }
        }
        return outputs;
    }

    private String parseParameters(String parameters) {
        List<ParameterDefinition> definitions = new ArrayList<>();
        if (parameters == null || parameters.isBlank()) {
            return writeJson(definitions);
        }
        for (String parameter : parameters.split(",")) {
            String normalized = parameter.trim();
            int separator = normalized.lastIndexOf(' ');
            if (separator <= 0 || separator == normalized.length() - 1) {
                throw new IllegalArgumentException("无法解析参数: " + normalized);
            }
            definitions.add(new ParameterDefinition(
                    normalized.substring(0, separator).trim(),
                    normalized.substring(separator + 1).trim()
            ));
        }
        return writeJson(definitions);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("参数配置序列化失败", exception);
        }
    }

    private String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return Jsoup.parse(html).wholeText().trim();
    }

    private int parseDifficulty(String difficulty) {
        return switch (difficulty.toUpperCase(Locale.ROOT)) {
            case "EASY" -> 1;
            case "MEDIUM" -> 2;
            case "HARD" -> 3;
            default -> 1;
        };
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private record GraphQlRequest(String query, Variables variables) {
    }

    private record Variables(String titleSlug) {
    }

    private record ParameterDefinition(String type, String name) {
    }
}
