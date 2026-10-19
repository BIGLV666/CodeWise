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

    @Test
    void generatesDeterministicListNodeInputsAsIntArrays() throws Exception {
        String config = "[{\"type\":\"ListNode\",\"name\":\"head\"}]";

        List<String> first = generator.generate(config, 20, 2026L);
        List<String> second = generator.generate(config, 20, 2026L);

        assertEquals(first, second);
        assertEquals(20, first.size());
        assertEquals(20, first.stream().distinct().count());
        for (String input : first) {
            // 判题侧按 int[] 反序列化再建链表，因此这里必须是纯整数数组
            var values = new ObjectMapper().readTree(input);
            assertTrue(values.isArray());
            assertTrue(values.size() <= 20);
            for (var value : values) {
                assertTrue(value.isInt());
                assertTrue(value.asInt() >= -100 && value.asInt() <= 100);
            }
        }
    }

    @Test
    void honoursParameterValueRanges() throws Exception {
        // 题面写「正整数」「数字 0~9」「长度不超过 8」时，数据必须跟着走
        String config = """
                [
                  {"type":"int","name":"depth","min":1,"max":1000},
                  {"type":"ListNode","name":"head","min":0,"max":9,"minSize":1,"maxSize":8},
                  {"type":"String","name":"s","minSize":3,"maxSize":3},
                  {"type":"int[][]","name":"grid","minSize":1,"maxSize":2,"innerMinSize":1,"innerMaxSize":3,"min":10,"max":20}
                ]
                """;

        List<String> inputs = generator.generate(config, 30, 7L);

        assertEquals(30, inputs.size());
        for (String input : inputs) {
            String[] lines = input.split("\\R", -1);
            assertEquals(4, lines.length);
            int depth = Integer.parseInt(lines[0]);
            assertTrue(depth >= 1 && depth <= 1000, lines[0]);

            var head = new ObjectMapper().readTree(lines[1]);
            assertTrue(head.size() >= 1 && head.size() <= 8, lines[1]);
            for (var node : head) {
                assertTrue(node.asInt() >= 0 && node.asInt() <= 9, lines[1]);
            }

            assertEquals(3, lines[2].length(), lines[2]);

            var grid = new ObjectMapper().readTree(lines[3]);
            assertTrue(grid.size() >= 1 && grid.size() <= 2, lines[3]);
            for (var row : grid) {
                assertTrue(row.size() >= 1 && row.size() <= 3, lines[3]);
                for (var cell : row) {
                    assertTrue(cell.asInt() >= 10 && cell.asInt() <= 20, lines[3]);
                }
            }
        }
    }

    @Test
    void clampsAbsurdSizesAndSwapsInvertedRanges() throws Exception {
        String config = """
                [
                  {"type":"int[]","name":"big","minSize":100000,"maxSize":2},
                  {"type":"int","name":"value","min":50,"max":-50},
                  {"type":"String[]","name":"words","minSize":1,"maxSize":1,"elementMinSize":2,"elementMaxSize":2}
                ]
                """;

        List<String> inputs = generator.generate(config, 10, 3L);

        for (String input : inputs) {
            String[] lines = input.split("\\R", -1);
            var big = new ObjectMapper().readTree(lines[0]);
            // 尺寸上限 200：模型给出 100000 也不能真的生成十万个元素
            assertTrue(big.size() <= 200, lines[0]);
            int value = Integer.parseInt(lines[1]);
            assertTrue(value >= -50 && value <= 50, lines[1]);
            var words = new ObjectMapper().readTree(lines[2]);
            assertEquals(1, words.size());
            assertEquals(2, words.get(0).asText().length());
        }
    }

    @Test
    void keepsPlatformDefaultsWhenNoRangeGiven() throws Exception {
        String config = "[{\"type\":\"int\",\"name\":\"value\"}]";

        for (String input : generator.generate(config, 20, 99L)) {
            int value = Integer.parseInt(input);
            assertTrue(value >= -1000 && value <= 1000);
        }
    }

    @Test
    void generatesDeterministicIntMatrixInputs() throws Exception {        String config = "[{\"type\":\"int[][]\",\"name\":\"grid\"}]";

        List<String> first = generator.generate(config, 20, 11L);
        List<String> second = generator.generate(config, 20, 11L);

        assertEquals(first, second);
        assertEquals(20, first.size());
        for (String input : first) {
            var matrix = new ObjectMapper().readTree(input);
            assertTrue(matrix.isArray());
            assertTrue(matrix.size() <= 5);
            for (var row : matrix) {
                assertTrue(row.isArray());
                assertTrue(row.size() <= 5);
            }
        }
    }
}
