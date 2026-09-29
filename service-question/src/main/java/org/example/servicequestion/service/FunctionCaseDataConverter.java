package org.example.servicequestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.ErrorCode;
import org.example.servicequestion.exception.QuestionErrorCode;
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
            throw new BizException(QuestionErrorCode.TESTCASE_REQUIRED);
        }
        return new FunctionSampleVo(
                normalizeInput(sample.getInput(), parameterConfig),
                normalizeOutput(sample.getOutput(), outputType)
        );
    }

    public String normalizeInput(String input, String parameterConfig) {
        JsonNode parameters = readJson(parameterConfig, QuestionErrorCode.FUNCTION_PARAM_CONFIG_INVALID);
        if (!parameters.isArray()) {
            throw new BizException(QuestionErrorCode.FUNCTION_PARAM_CONFIG_INVALID);
        }

        String source = input == null ? "" : input;
        String[] values = source.split("\\R", -1);
        if (values.length != parameters.size()) {
            throw new BizException(
                    QuestionErrorCode.TESTCASE_INPUT_COUNT_MISMATCH,
                    parameters.size(),
                    values.length
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
        return "String".equals(type)
                || "char".equals(type)
                || "Character".equals(type);

    }


    private JsonNode readJson(String value, ErrorCode errorCode) {
        if (value == null || value.isBlank()) {
            throw new BizException(errorCode);
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BizException(errorCode, exception);
        }
    }
}
