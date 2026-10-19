package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.dto.ai.FunctionParameterRange;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionParameterRangeMergerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FunctionParameterRangeMerger merger = new FunctionParameterRangeMerger(objectMapper);

    @Test
    void mergesRangesByNameOnly() throws Exception {
        String config = """
                [{"type":"int[]","name":"nums"},{"type":"int","name":"target"}]
                """;
        List<FunctionParameterRange> ranges = List.of(
                range("target", 0L, 100L, null, null),
                range("不存在", 1L, 2L, null, null)
        );

        JsonNode merged = objectMapper.readTree(merger.merge(config, ranges));

        assertEquals(2, merged.size());
        assertEquals("int[]", merged.get(0).path("type").asText());
        assertTrue(merged.get(0).path("min").isMissingNode());
        assertEquals(0L, merged.get(1).path("min").asLong());
        assertEquals(100L, merged.get(1).path("max").asLong());
    }

    @Test
    void keepsTypesAndNamesUntouched() throws Exception {
        String config = "[{\"type\":\"int\",\"name\":\"value\"}]";

        JsonNode merged = objectMapper.readTree(merger.merge(config, List.of(range("value", 1L, 3L, 2, 5))));

        assertEquals("int", merged.get(0).path("type").asText());
        assertEquals("value", merged.get(0).path("name").asText());
        assertEquals(1L, merged.get(0).path("min").asLong());
        assertEquals(2, merged.get(0).path("minSize").asInt());
    }

    @Test
    void ignoresNamesAndEmptyRanges() {
        String config = "[{\"type\":\"int\",\"name\":\"value\"}]";

        assertEquals(config, merger.merge(config, List.of()));
        assertEquals(config, merger.merge(config, null));

        FunctionParameterRange nameless = new FunctionParameterRange();
        nameless.setMin(1L);
        assertEquals(config, merger.merge(config, List.of(nameless)));
    }

    @Test
    void rejectsMalformedParameterConfig() {
        assertThrows(BizException.class, () -> merger.merge("{not-json", List.of(range("value", 1L, 2L, null, null))));
    }

    private FunctionParameterRange range(String name, Long min, Long max, Integer minSize, Integer maxSize) {
        FunctionParameterRange range = new FunctionParameterRange();
        range.setName(name);
        range.setMin(min);
        range.setMax(max);
        range.setMinSize(minSize);
        range.setMaxSize(maxSize);
        return range;
    }

    @Test
    void passesAllowedCharsThrough() throws Exception {
        FunctionParameterRange range = new FunctionParameterRange();
        range.setName("s");
        range.setAllowedChars("123456789#");

        JsonNode merged = objectMapper.readTree(merger.merge("[{\"type\":\"String\",\"name\":\"s\"}]", List.of(range)));

        assertEquals("123456789#", merged.get(0).path("allowedChars").asText());
    }

    @Test
    void onlySupportedKeysSurvive() throws Exception {
        FunctionParameterRange range = new FunctionParameterRange();
        range.setName("value");
        range.setMin(1L);
        range.setInnerMaxSize(3);
        List<FunctionParameterRange> ranges = new ArrayList<>(List.of(range));

        JsonNode merged = objectMapper.readTree(merger.merge("[{\"type\":\"int\",\"name\":\"value\"}]", ranges));

        assertEquals(1L, merged.get(0).path("min").asLong());
        assertEquals(3, merged.get(0).path("innerMaxSize").asInt());
        // type、name + 两个受支持的取值域键
        assertEquals(4, merged.get(0).size());
    }
}
