package org.example.servicequestion.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.serviceapi.dto.ai.FunctionParameterRange;
import org.example.serviceapi.exception.BizException;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「参数取值域建议」合并进参数配置，产出一份只用于<b>随机输入生成</b>的配置。
 *
 * <p>三条约束：</p>
 * <ol>
 *   <li>只按参数名匹配：模型给出的名字对不上签名就丢弃，绝不新增/改类型，
 *       否则生成的输入与判题侧 {@code Main} 的读取顺序会对不上。</li>
 *   <li>只接受白名单键且必须是数字：模型输出不可信，塞进来一个字符串会把
 *       生成器 NPE。</li>
 *   <li>合并结果只服务生成：落库的 {@code function_config.parameter_config} 保持原样
 *       （判题与前端读的是它，多出来的键会污染它们的语义）。</li>
 * </ol>
 */
@Component
@Slf4j
public class FunctionParameterRangeMerger {

    /** 与随机输入生成器支持的键一一对应。 */
    private static final List<String> SUPPORTED_KEYS = List.of(
            "min", "max",
            "minSize", "maxSize",
            "innerMinSize", "innerMaxSize",
            "elementMinSize", "elementMaxSize"
    );

    private final ObjectMapper objectMapper;

    public FunctionParameterRangeMerger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param parameterConfig 落库的参数配置（类型+名称）
     * @param ranges          取值域建议，可为空
     * @return 合并后的参数配置 JSON；没有任何有效建议时返回原配置
     */
    public String merge(String parameterConfig, List<FunctionParameterRange> ranges) {
        if (parameterConfig == null || parameterConfig.isBlank() || ranges == null || ranges.isEmpty()) {
            return parameterConfig;
        }
        JsonNode parameters;
        try {
            parameters = objectMapper.readTree(parameterConfig);
        } catch (JsonProcessingException exception) {
            throw new BizException(QuestionErrorCode.FUNCTION_PARAM_CONFIG_INVALID, exception);
        }
        if (parameters == null || !parameters.isArray()) {
            throw new BizException(QuestionErrorCode.FUNCTION_PARAM_CONFIG_INVALID);
        }

        Map<String, FunctionParameterRange> byName = new LinkedHashMap<>();
        for (FunctionParameterRange range : ranges) {
            if (range == null || range.getName() == null || range.getName().isBlank()) {
                continue;
            }
            byName.put(range.getName().trim(), range);
        }
        if (byName.isEmpty()) {
            return parameterConfig;
        }

        ArrayNode merged = objectMapper.createArrayNode();
        for (JsonNode parameter : parameters) {
            ObjectNode entry = parameter.deepCopy();
            FunctionParameterRange range = entry.hasNonNull("name")
                    ? byName.get(entry.get("name").asText().trim())
                    : null;
            if (range == null) {
                merged.add(entry);
                continue;
            }
            Map<String, Long> values = collect(range);
            for (Map.Entry<String, Long> value : values.entrySet()) {
                entry.put(value.getKey(), value.getValue());
            }
            if (range.getAllowedChars() != null && !range.getAllowedChars().isBlank()) {
                entry.put("allowedChars", range.getAllowedChars());
            }
            merged.add(entry);
        }
        return merged.toString();
    }

    private Map<String, Long> collect(FunctionParameterRange range) {
        Map<String, Long> values = new LinkedHashMap<>();
        put(values, "min", range.getMin());
        put(values, "max", range.getMax());
        put(values, "minSize", range.getMinSize());
        put(values, "maxSize", range.getMaxSize());
        put(values, "innerMinSize", range.getInnerMinSize());
        put(values, "innerMaxSize", range.getInnerMaxSize());
        put(values, "elementMinSize", range.getElementMinSize());
        put(values, "elementMaxSize", range.getElementMaxSize());
        return values;
    }

    private void put(Map<String, Long> values, String key, Number value) {
        if (!SUPPORTED_KEYS.contains(key) || value == null) {
            return;
        }
        values.put(key, value.longValue());
    }
}
