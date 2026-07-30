package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.servicequestion.vo.FunctionSampleVo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FunctionCaseDataConverterTest {

    private final FunctionCaseDataConverter converter =
            new FunctionCaseDataConverter(new ObjectMapper());

    @Test
    void shouldNormalizeLeetCodeStringAndIntegerParameters() {
        FunctionSampleVo result = converter.normalize(
                new FunctionSampleVo(
                        "\"PAYPALISHIRING\"\n3",
                        "\"PAHNAPLSIIGYIR\""
                ),
                """
                [
                  {"type":"String","name":"s"},
                  {"type":"int","name":"numRows"}
                ]
                """,
                "String"
        );

        assertEquals("PAYPALISHIRING\n3", result.getInput());
        assertEquals("PAHNAPLSIIGYIR", result.getOutput());
    }

    @Test
    void shouldAcceptRawValuesFromFrontend() {
        FunctionSampleVo result = converter.normalize(
                new FunctionSampleVo("hello world\n2", "hello"),
                """
                [
                  {"type":"String","name":"text"},
                  {"type":"int","name":"count"}
                ]
                """,
                "String"
        );

        assertEquals("hello world\n2", result.getInput());
        assertEquals("hello", result.getOutput());
    }

    @Test
    void shouldPreserveEmptyStringParameter() {
        FunctionSampleVo result = converter.normalize(
                new FunctionSampleVo("\"\"", "0"),
                """
                [{"type":"String","name":"s"}]
                """,
                "int"
        );

        assertEquals("", result.getInput());
        assertEquals("0", result.getOutput());
    }
}
