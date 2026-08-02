package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void generatesDeterministicValidTrees() throws Exception {
        String config = "[{\"type\":\"TreeNode\",\"name\":\"root\"}]";

        List<String> first = generator.generate(config, 20, 42L);
        List<String> second = generator.generate(config, 20, 42L);

        assertEquals(first, second);
        assertEquals(20, first.size());
        for (String input : first) {
            var values = new ObjectMapper().readTree(input);
            assertTrue(values.isArray());
            if (values.isEmpty()) {
                continue;
            }
            assertFalse(values.get(0).isNull());
            int pendingParents = 1;
            int index = 1;
            while (index < values.size()) {
                assertTrue(pendingParents > 0);
                pendingParents--;
                for (int child = 0; child < 2 && index < values.size(); child++, index++) {
                    if (!values.get(index).isNull()) {
                        pendingParents++;
                    }
                }
            }
        }
    }
}
