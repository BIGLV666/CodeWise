package org.example.servicequestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Component
public class FunctionRandomInputGenerator {

    private static final String STRING_CHARACTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final ObjectMapper objectMapper;

    public FunctionRandomInputGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> generate(String parameterConfig, int count, long seed) {
        JsonNode parameters = readParameters(parameterConfig);
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("暂不支持无参数函数的随机测试生成");
        }

        Random random = new Random(seed);
        Set<String> inputs = new LinkedHashSet<>(count);
        int maxAttempts = Math.max(100, count * 30);
        for (int attempt = 0; attempt < maxAttempts && inputs.size() < count; attempt++) {
            List<String> values = new ArrayList<>(parameters.size());
            for (JsonNode parameter : parameters) {
                values.add(generateValue(parameter.path("type").asText(), random));
            }
            inputs.add(String.join("\n", values));
        }
        if (inputs.size() < count) {
            throw new IllegalStateException("随机输入去重后数量不足，请更换随机种子重试");
        }
        return new ArrayList<>(inputs);
    }

    private JsonNode readParameters(String parameterConfig) {
        try {
            JsonNode parameters = objectMapper.readTree(parameterConfig);
            if (parameters == null || !parameters.isArray()) {
                throw new IllegalArgumentException("函数参数配置格式错误");
            }
            return parameters;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("函数参数配置格式错误", exception);
        }
    }

    private String generateValue(String type, Random random) {
        return switch (type) {
            case "int" -> String.valueOf(random.nextInt(2001) - 1000);
            case "long" -> String.valueOf(random.nextLong(-100_000L, 100_001L));
            case "String" -> randomString(random, random.nextInt(21));
            case "int[]", "int []" -> writeJson(randomIntArray(random));
            case "String[]", "String []" -> writeJson(randomStringArray(random));
            case "TreeNode" -> writeJson(randomTree(random));
            default -> throw new IllegalArgumentException("随机生成暂不支持参数类型: " + type);
        };
    }

    private int[] randomIntArray(Random random) {
        int[] values = new int[random.nextInt(21)];
        for (int index = 0; index < values.length; index++) {
            values[index] = random.nextInt(201) - 100;
        }
        return values;
    }

    private List<Integer> randomTree(Random random) {
        if (random.nextInt(10) == 0) {
            return List.of();
        }

        List<Integer> values = new ArrayList<>();
        values.add(random.nextInt(201) - 100);
        int pendingParents = 1;
        int nodeCount = 1;
        int targetNodeCount = random.nextInt(15) + 1;

        while (pendingParents > 0 && nodeCount < targetNodeCount) {
            pendingParents--;
            for (int child = 0; child < 2; child++) {
                boolean createChild = nodeCount < targetNodeCount && random.nextInt(100) < 70;
                if (createChild) {
                    values.add(random.nextInt(201) - 100);
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

    private String[] randomStringArray(Random random) {
        String[] values = new String[random.nextInt(11)];
        for (int index = 0; index < values.length; index++) {
            values[index] = randomString(random, random.nextInt(11));
        }
        return values;
    }

    private String randomString(Random random, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(STRING_CHARACTERS.charAt(random.nextInt(STRING_CHARACTERS.length())));
        }
        return value.toString();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("随机输入序列化失败", exception);
        }
    }
}
