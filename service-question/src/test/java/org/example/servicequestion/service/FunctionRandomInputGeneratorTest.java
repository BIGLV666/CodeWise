package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FunctionRandomInputGeneratorTest {

    private final FunctionRandomInputGenerator generator =
            new FunctionRandomInputGenerator(new ObjectMapper());

    @Test
    void generatesDeterministicUniqueInputsForSupportedTypes() {
        String config = """
                [
                  {"type":"int","name":"value"},
                  {"type":"String","name":"text"},
                  {"type":"int[]","name":"numbers"},
                  {"type":"String[]","name":"words"}
                ]
                """;

        List<String> first = generator.generate(config, 10, 123L);
        List<String> second = generator.generate(config, 10, 123L);

        assertEquals(first, second);
        assertEquals(10, first.size());
        assertEquals(10, first.stream().distinct().count());
        assertEquals(4, first.getFirst().split("\\R", -1).length);
    }

    @Test
    void rejectsUnsupportedParameterType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> generator.generate("[{\"type\":\"TreeNode\",\"name\":\"root\"}]", 1, 1L)
        );
    }
}
