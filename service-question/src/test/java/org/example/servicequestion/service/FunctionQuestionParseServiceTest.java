package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.servicequestion.dto.FunctionTestCaseDto;
import org.example.servicequestion.entry.FunctionConfig;
import org.example.servicequestion.entry.FunctionTestCase;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.enums.QuestionType;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.mapper.FunctionTestCaseMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.vo.FunctionParseVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionQuestionParseServiceTest {

    private final FunctionCaseDataConverter caseDataConverter =
            new FunctionCaseDataConverter(new ObjectMapper());

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private FunctionConfigMapper functionConfigMapper;

    @Mock
    private FunctionTestCaseMapper functionTestCaseMapper;

    @Test
    void insertTestCasesAddsHiddenNonSampleCases() {
        Question question = Question.builder()
                .questionId(1L)
                .questionType(QuestionType.FUNCTION)
                .timeLimit(3000)
                .memoryLimit(512)
                .build();
        FunctionConfig functionConfig = FunctionConfig.builder()
                .questionId(1L)
                .parameterConfig("[{\"type\":\"int\",\"name\":\"value\"}]")
                .outputType("int")
                .build();
        FunctionTestCase existing = FunctionTestCase.builder().sortOrder(3).build();
        when(questionMapper.selectById(1L)).thenReturn(question);
        when(functionConfigMapper.selectOne(any())).thenReturn(functionConfig);
        when(functionTestCaseMapper.selectOne(any())).thenReturn(existing);

        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );
        FunctionTestCaseDto first = new FunctionTestCaseDto();
        first.setInput("1");
        first.setOutput("2");
        FunctionTestCaseDto second = new FunctionTestCaseDto();
        second.setInput("3");
        second.setOutput("4");

        assertEquals(2, service.insertTestCases(1L, List.of(first, second)));
        verify(functionTestCaseMapper).insert(anyCollection());
        verify(questionMapper, never()).updateById(any(Question.class));
    }

    @Test
    void insertTestCasesRejectsNonFunctionQuestion() {
        Question question = Question.builder()
                .questionId(1L)
                .questionType(QuestionType.ACM)
                .build();
        when(questionMapper.selectById(1L)).thenReturn(question);

        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );

        FunctionTestCaseDto testCase = new FunctionTestCaseDto();
        testCase.setInput("1");
        testCase.setOutput("2");
        assertThrows(IllegalArgumentException.class, () -> service.insertTestCases(1L, List.of(testCase)));
        verify(functionTestCaseMapper, never()).insert(anyCollection());
    }

    @Test
    void insertTestCasesReportsDuplicateInputAndOutput() {
        Question question = Question.builder()
                .questionId(1L)
                .questionType(QuestionType.FUNCTION)
                .timeLimit(2000)
                .memoryLimit(256)
                .build();
        FunctionConfig functionConfig = FunctionConfig.builder()
                .questionId(1L)
                .parameterConfig("[{\"type\":\"int\",\"name\":\"value\"}]")
                .outputType("int")
                .build();
        when(questionMapper.selectById(1L)).thenReturn(question);
        when(functionConfigMapper.selectOne(any())).thenReturn(functionConfig);
        when(functionTestCaseMapper.selectOne(any())).thenReturn(null);
        doThrow(new DuplicateKeyException("duplicate"))
                .when(functionTestCaseMapper)
                .insert(anyCollection());

        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );
        FunctionTestCaseDto testCase = new FunctionTestCaseDto();
        testCase.setInput("1");
        testCase.setOutput("2");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.insertTestCases(1L, List.of(testCase))
        );
        assertEquals("测试用例已存在", exception.getMessage());
    }

    @Test
    void parseJavaSignatureDoesNotIncludePublicInOutputType() {
        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );
        FunctionParseVo result = new FunctionParseVo();

        service.parseJavaSignature(
                """
                class Solution {
                    public int myAtoi(String s) {
                        return 0;
                    }
                }
                """,
                result
        );

        assertEquals("int", result.getOutputType());
        assertEquals("myAtoi", result.getMethodName());
    }

    @Test
    void parseOutputsFromLeetCodeExampleBlocks() {
        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );
        String html = """
                <div class="example-block">
                    <p><strong>输入：</strong><span class="example-io">s = "42"</span></p>
                    <p><strong>输出：</strong><span class="example-io">42</span></p>
                </div>
                <div class="example-block">
                    <p><span class="example-io"><b>输出：</b>0</span></p>
                </div>
                """;

        assertEquals(List.of("42", "0"), service.parseExampleOutputs(html));
    }
}
