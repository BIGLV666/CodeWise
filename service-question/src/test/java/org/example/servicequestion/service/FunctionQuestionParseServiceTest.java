package org.example.servicequestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceapi.exception.BizException;
import org.example.servicequestion.dto.FunctionDto;
import org.example.servicequestion.dto.FunctionTestCaseDto;
import org.example.servicequestion.entry.FunctionConfig;
import org.example.servicequestion.entry.FunctionTestCase;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.enums.QuestionType;
import org.example.servicequestion.exception.QuestionErrorCode;
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
        assertThrows(BizException.class, () -> service.insertTestCases(1L, List.of(testCase)));
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

        BizException exception = assertThrows(
                BizException.class,
                () -> service.insertTestCases(1L, List.of(testCase))
        );
        assertEquals(QuestionErrorCode.TESTCASE_DUPLICATED.getCode(), exception.getCode());
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
    void insertWithPrivateStatusKeepsQuestionOffShelf() {
        Question[] captured = new Question[1];
        when(questionMapper.insert(any(Question.class))).thenAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            captured[0].setQuestionId(99L);
            return 1;
        });
        when(functionConfigMapper.insert(any(FunctionConfig.class))).thenReturn(1);

        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );
        FunctionDto dto = new FunctionDto();
        dto.setTitle("批量导入题");
        dto.setDescription("题面");
        dto.setMethodName("solve");
        dto.setClassName("Solution");
        dto.setParameterConfig("[{\"type\":\"int\",\"name\":\"value\"}]");
        dto.setOutputType("int");

        assertEquals(99L, service.insert(dto, 3));
        assertEquals(3, captured[0].getStatus());
    }

    @Test
    void promoteSamplesPublishesQuestionWhenRequested() {
        Question question = Question.builder()
                .questionId(8L)
                .questionType(QuestionType.FUNCTION)
                .status(3)
                .build();
        FunctionTestCase testCase = FunctionTestCase.builder()
                .functionTestCaseId(21L)
                .questionId(8L)
                .inputData("5")
                .expectedOutput("25")
                .sortOrder(1)
                .isSample(0)
                .isHidden(1)
                .build();
        when(questionMapper.selectById(8L)).thenReturn(question);
        when(functionTestCaseMapper.selectList(any())).thenReturn(List.of(testCase));
        when(functionTestCaseMapper.updateById(any(FunctionTestCase.class))).thenReturn(1);
        when(questionMapper.updateById(any(Question.class))).thenReturn(1);

        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );

        assertEquals(1, service.promoteSamples(8L, 1, true));
        assertEquals(1, question.getStatus());
        assertEquals("5", question.getSampleInput());
        assertEquals("25", question.getSampleOutput());
    }

    @Test
    void promoteSamplesMarksEarliestCasesAndBackfillsQuestion() {
        Question question = Question.builder()
                .questionId(7L)
                .questionType(QuestionType.FUNCTION)
                .build();
        FunctionTestCase firstCase = FunctionTestCase.builder()
                .functionTestCaseId(11L)
                .questionId(7L)
                .inputData("1")
                .expectedOutput("2")
                .sortOrder(1)
                .isSample(0)
                .isHidden(1)
                .build();
        when(questionMapper.selectById(7L)).thenReturn(question);
        when(functionTestCaseMapper.selectList(any())).thenReturn(List.of(firstCase));
        when(functionTestCaseMapper.updateById(any(FunctionTestCase.class))).thenReturn(1);
        when(questionMapper.updateById(any(Question.class))).thenReturn(1);

        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );

        assertEquals(1, service.promoteSamples(7L, 1));
        assertEquals(1, firstCase.getIsSample());
        assertEquals(0, firstCase.getIsHidden());
        assertEquals("1", question.getSampleInput());
        assertEquals("2", question.getSampleOutput());
        verify(questionMapper).updateById(question);
    }

    @Test
    void promoteSamplesRejectsWhenNoHiddenCaseLeft() {
        Question question = Question.builder()
                .questionId(7L)
                .questionType(QuestionType.FUNCTION)
                .build();
        when(questionMapper.selectById(7L)).thenReturn(question);
        when(functionTestCaseMapper.selectList(any())).thenReturn(List.of());

        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );

        BizException exception = assertThrows(BizException.class, () -> service.promoteSamples(7L, 1));
        assertEquals(QuestionErrorCode.SAMPLE_PROMOTE_NO_CASE.getCode(), exception.getCode());
        verify(questionMapper, never()).updateById(any(Question.class));
    }

    @Test
    void promoteSamplesRejectsNonFunctionQuestion() {
        Question question = Question.builder()
                .questionId(7L)
                .questionType(QuestionType.ACM)
                .build();
        when(questionMapper.selectById(7L)).thenReturn(question);

        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );

        BizException exception = assertThrows(BizException.class, () -> service.promoteSamples(7L, 1));
        assertEquals(QuestionErrorCode.NOT_FUNCTION_QUESTION.getCode(), exception.getCode());
    }

    @Test
    void promoteSamplesRejectsCountOutOfRange() {
        FunctionQuestionParseService service = new FunctionQuestionParseService(
                questionMapper,
                functionConfigMapper,
                functionTestCaseMapper,
                caseDataConverter
        );

        BizException exception = assertThrows(BizException.class, () -> service.promoteSamples(7L, 99));
        assertEquals(QuestionErrorCode.SAMPLE_PROMOTE_COUNT_INVALID.getCode(), exception.getCode());
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
