package org.example.servicequestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 按参数配置生成随机输入。
 *
 * <p>参数配置除 {@code type}/{@code name} 外，还可以带上<b>取值域</b>（可选，缺省用平台默认值），
 * 让「题面里写的约束」与「实际生成的测试数据」保持一致——否则会出现 depth 为负数、
 * 链表节点值是 -40/65 这类语义无效的用例，用户按题面写对的解也会被判错。</p>
 *
 * <p>支持的键（全部可选，非法值回退默认并做上限裁剪）：</p>
 * <ul>
 *   <li>{@code int}/{@code long}：{@code min}/{@code max}（数值范围）</li>
 *   <li>{@code String}：{@code minSize}/{@code maxSize}（长度）</li>
 *   <li>{@code int[]}：{@code minSize}/{@code maxSize}（长度）+ {@code min}/{@code max}（元素值）</li>
 *   <li>{@code String[]}：{@code minSize}/{@code maxSize}（个数）+ {@code elementMinSize}/{@code elementMaxSize}（单个串长度）</li>
 *   <li>{@code int[][]}：{@code minSize}/{@code maxSize}（行数）+ {@code innerMinSize}/{@code innerMaxSize}（列数）+ {@code min}/{@code max}（元素值）</li>
 *   <li>{@code ListNode}：{@code minSize}/{@code maxSize}（节点数）+ {@code min}/{@code max}（节点值，如数字题应约束到 0~9）</li>
 *   <li>{@code TreeNode}：{@code minSize}/{@code maxSize}（节点数）+ {@code min}/{@code max}（节点值）</li>
 * </ul>
 */
@Component
public class FunctionRandomInputGenerator {

    private static final String STRING_CHARACTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** 取值域裁剪上限：避免模型给出 10^9 长度的数组把判题拖垮。 */
    private static final int MAX_SIZE = 200;
    private static final long MAX_ABS_VALUE = 1_000_000_000L;

    private final ObjectMapper objectMapper;

    public FunctionRandomInputGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> generate(String parameterConfig, int count, long seed) {
        JsonNode parameters = readParameters(parameterConfig);
        if (parameters.isEmpty()) {
            throw new BizException(QuestionErrorCode.RANDOM_NO_PARAM_UNSUPPORTED);
        }

        Random random = new Random(seed);
        Set<String> inputs = new LinkedHashSet<>(count);
        int maxAttempts = Math.max(100, count * 30);
        for (int attempt = 0; attempt < maxAttempts && inputs.size() < count; attempt++) {
            List<String> values = new ArrayList<>(parameters.size());
            for (JsonNode parameter : parameters) {
                values.add(generateValue(parameter, random));
            }
            inputs.add(String.join("\n", values));
        }
        if (inputs.size() < count) {
            throw new BizException(QuestionErrorCode.RANDOM_INPUT_INSUFFICIENT);
        }
        return new ArrayList<>(inputs);
    }

    private JsonNode readParameters(String parameterConfig) {
        try {
            JsonNode parameters = objectMapper.readTree(parameterConfig);
            if (parameters == null || !parameters.isArray()) {
                throw new BizException(QuestionErrorCode.FUNCTION_PARAM_CONFIG_INVALID);
            }
            return parameters;
        } catch (JsonProcessingException exception) {
            throw new BizException(QuestionErrorCode.FUNCTION_PARAM_CONFIG_INVALID, exception);
        }
    }

    private String generateValue(JsonNode parameter, Random random) {
        String type = parameter.path("type").asText();
        return switch (type) {
            case "int" -> String.valueOf((int) value(random, parameter, -1000, 1000));
            case "long" -> String.valueOf(value(random, parameter, -100_000L, 100_001L - 1));
            case "String" -> randomString(
                    random,
                    size(random, parameter, "minSize", "maxSize", 0, 20),
                    charset(parameter)
            );
            case "int[]", "int []" -> writeJson(randomIntArray(random, parameter));
            case "String[]", "String []" -> writeJson(randomStringArray(random, parameter));
            // 链表与二叉树一样按数组下发（判题侧的 CodeBuild 会把数组还原成节点结构）
            case "ListNode" -> writeJson(randomIntArray(random, parameter));
            case "int[][]", "int [][]" -> writeJson(randomIntMatrix(random, parameter));
            case "TreeNode" -> writeJson(randomTree(random, parameter));
            default -> throw new BizException(QuestionErrorCode.RANDOM_TYPE_UNSUPPORTED, type);
        };
    }

    // ---------------------------------------------------------------- 取值域解析

    private long value(Random random, JsonNode parameter, long defaultMin, long defaultMax) {
        long[] range = resolveRange(parameter, "min", defaultMin, "max", defaultMax, MAX_ABS_VALUE);
        return nextLongInclusive(random, range[0], range[1]);
    }

    private int size(Random random, JsonNode parameter, String minField, String maxField, int defaultMin, int defaultMax) {
        long[] range = resolveRange(parameter, minField, defaultMin, maxField, defaultMax, MAX_SIZE);
        // 尺寸不能为负，裁剪后仍可能拿到负数（模型给了负 min），统一夹到 0
        long min = Math.max(0, Math.min(range[0], MAX_SIZE));
        long max = Math.max(0, Math.min(range[1], MAX_SIZE));
        if (min > max) {
            long swap = min;
            min = max;
            max = swap;
        }
        return (int) nextLongInclusive(random, min, max);
    }

    private long[] resolveRange(
            JsonNode parameter,
            String minField,
            long defaultMin,
            String maxField,
            long defaultMax,
            long limit
    ) {
        long min = clamp(number(parameter, minField, defaultMin), limit);
        long max = clamp(number(parameter, maxField, defaultMax), limit);
        if (min > max) {
            long swap = min;
            min = max;
            max = swap;
        }
        return new long[]{min, max};
    }

    private long number(JsonNode parameter, String field, long fallback) {
        JsonNode value = parameter.get(field);
        if (value == null || !value.isNumber()) {
            return fallback;
        }
        return value.asLong();
    }

    private long clamp(long value, long limit) {
        if (value > limit) {
            return limit;
        }
        if (value < -limit) {
            return -limit;
        }
        return value;
    }

    private long nextLongInclusive(Random random, long min, long max) {
        if (min >= max) {
            return min;
        }
        long bound = max - min + 1;
        if (bound <= 0) {
            return random.nextLong();
        }
        return min + Math.floorMod(random.nextLong(), bound);
    }

    // ---------------------------------------------------------------- 各类型生成

    private int[] randomIntArray(Random random, JsonNode parameter) {
        int[] values = new int[size(random, parameter, "minSize", "maxSize", 0, 20)];
        for (int index = 0; index < values.length; index++) {
            values[index] = (int) value(random, parameter, -100, 100);
        }
        return values;
    }

    private int[][] randomIntMatrix(Random random, JsonNode parameter) {
        int rows = size(random, parameter, "minSize", "maxSize", 0, 5);
        int[][] values = new int[rows][];
        for (int row = 0; row < rows; row++) {
            int columns = size(random, parameter, "innerMinSize", "innerMaxSize", 0, 5);
            values[row] = new int[columns];
            for (int column = 0; column < columns; column++) {
                values[row][column] = (int) value(random, parameter, -100, 100);
            }
        }
        return values;
    }

    private List<Integer> randomTree(Random random, JsonNode parameter) {
        boolean defaultShape = !parameter.has("minSize") && !parameter.has("maxSize");
        int valueLow = (int) clamp(Math.min(number(parameter, "min", -100), number(parameter, "max", 100)), MAX_ABS_VALUE);
        int valueHigh = (int) clamp(Math.max(number(parameter, "min", -100), number(parameter, "max", 100)), MAX_ABS_VALUE);

        int targetNodeCount;
        if (defaultShape) {
            // 保留默认行为：约 10% 概率空树，否则 1~15 个节点
            if (random.nextInt(10) == 0) {
                return List.of();
            }
            targetNodeCount = 1 + random.nextInt(15);
        } else {
            int min = (int) Math.max(0, Math.min(number(parameter, "minSize", 0), MAX_SIZE));
            int max = (int) Math.max(0, Math.min(number(parameter, "maxSize", 15), MAX_SIZE));
            targetNodeCount = (int) nextLongInclusive(random, Math.min(min, max), Math.max(min, max));
        }
        if (targetNodeCount <= 0) {
            return List.of();
        }

        List<Integer> values = new ArrayList<>();
        values.add((int) nextLongInclusive(random, valueLow, valueHigh));
        int pendingParents = 1;
        int nodeCount = 1;

        while (pendingParents > 0 && nodeCount < targetNodeCount) {
            pendingParents--;
            for (int child = 0; child < 2; child++) {
                boolean createChild = nodeCount < targetNodeCount && random.nextInt(100) < 70;
                if (createChild) {
                    values.add((int) nextLongInclusive(random, valueLow, valueHigh));
                    pendingParents++;
                    nodeCount++;
                } else {
                    values.add(null);
                }
            }
        }

        int last = values.size() - 1;
        while (last >= 0 && values.get(last) == null) {
            last--;
        }
        return new ArrayList<>(values.subList(0, last + 1));
    }

    private String[] randomStringArray(Random random, JsonNode parameter) {
        String[] values = new String[size(random, parameter, "minSize", "maxSize", 0, 10)];
        for (int index = 0; index < values.length; index++) {
            values[index] = randomString(
                    random,
                    size(random, parameter, "elementMinSize", "elementMaxSize", 0, 10),
                    charset(parameter)
            );
        }
        return values;
    }

    private String randomString(Random random, int length, String charset) {
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(charset.charAt(random.nextInt(charset.length())));
        }
        return value.toString();
    }

    /**
     * 允许的字符集：{@code allowedChars} 里有可打印字符就用它，否则回退默认字符集。
     *
     * <p>模型可能给出中文描述（如「数字和字母」）而不是字面量，那种值直接忽略——
     * 拿它当字符集会生成一堆汉字，反而更糟。</p>
     */
    private String charset(JsonNode parameter) {
        JsonNode node = parameter.get("allowedChars");
        if (node == null || !node.isTextual()) {
            return STRING_CHARACTERS;
        }
        String raw = node.asText();
        StringBuilder sanitized = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (current >= 0x21 && current <= 0x7e && sanitized.indexOf(String.valueOf(current)) < 0) {
                sanitized.append(current);
            }
        }
        return sanitized.length() == 0 ? STRING_CHARACTERS : sanitized.toString();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(CommonErrorCode.INTERNAL_ERROR, exception);
        }
    }
}
