package org.example.servicequestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.servicequestion.vo.FunctionSampleVo;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class FunctionCaseDataConverter {

    private final ObjectMapper objectMapper;

    public FunctionCaseDataConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FunctionSampleVo normalize(
            FunctionSampleVo sample,
            String parameterConfig,
            String outputType
    ) {
        if (sample == null) {
            throw new IllegalArgumentException("函数测试用例不能为空");
        }
        return new FunctionSampleVo(
                normalizeInput(sample.getInput(), parameterConfig),
                normalizeOutput(sample.getOutput(), outputType)
        );
    }

    public String normalizeInput(String input, String parameterConfig) {
        JsonNode parameters = readJson(parameterConfig, "函数参数配置格式错误");
        if (!parameters.isArray()) {
            throw new IllegalArgumentException("函数参数配置必须是数组");
        }

        String source = input == null ? "" : input;
        String[] values = source.split("\\R", -1);
        if (values.length != parameters.size()) {
            throw new IllegalArgumentException(
                    "测试输入参数数量不匹配，期望 "
                            + parameters.size()
                            + " 个，实际 "
                            + values.length
                            + " 个"
            );
        }

        List<String> normalizedValues = new ArrayList<>(values.length);
        for (int index = 0; index < values.length; index++) {
            String type = parameters.get(index).path("type").asText();
            normalizedValues.add(normalizeValue(values[index], type));
        }
        return String.join("\n", normalizedValues);
    }

    public String normalizeOutput(String output, String outputType) {
        String source = output == null ? "" : output;
        return normalizeValue(source, outputType);
    }

    private String normalizeValue(String value, String type) {
        if (!isStringType(type)) {
            return value.trim();
        }

        String candidate = value.trim();
        if (candidate.isEmpty()) {
            return "";
        }

        try {
            JsonNode node = objectMapper.readTree(candidate);
            if (node != null && node.isTextual()) {
                return node.asText();
            }
        } catch (JsonProcessingException ignored) {
            // 前端允许直接提交原始字符串，不要求一定使用 JSON 引号。
        }
        return value;
    }

    private boolean isStringType(String type) {
        Deque <Character>a=new LinkedList<>();
        StringBuilder b=new StringBuilder();

        return "String".equals(type)
                || "char".equals(type)
                || "Character".equals(type);

    }


    private JsonNode readJson(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }
}
