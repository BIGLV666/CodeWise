package org.example.serviceai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.serviceai.ai.AiFacade;
import org.example.serviceai.ai.AiRequest;
import org.example.serviceai.ai.AiTarget;
import org.example.serviceai.exception.AiErrorCode;
import org.example.serviceapi.dto.ai.FunctionDescriptionRequest;
import org.example.serviceapi.dto.ai.FunctionDescriptionResponse;
import org.example.serviceapi.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionDescriptionServiceTest {

    @Mock
    private AiFacade aiFacade;

    private FunctionDescriptionService service(String response) {
        when(aiFacade.chat(any(AiRequest.class))).thenReturn(response);
        return new FunctionDescriptionService(
                aiFacade,
                new ObjectMapper(),
                "opencode-go",
                "deepseek-v4.1-flash",
                "codewise-function-description",
                4096,
                20000
        );
    }

    @Test
    void callsDesignatedProviderWithStableSessionInsteadOfAuto() {
        FunctionDescriptionService service = service("{\"description\":\"题面正文\"}");

        service.generate(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade).chat(captor.capture());
        AiRequest sent = captor.getValue();
        // 订阅制端点不在 AUTO 轮转里，必须按身份借用，否则拿不到 prompt cache 复用
        assertTrue(sent.target() instanceof AiTarget.PlatformProvider);
        assertEquals("opencode-go", ((AiTarget.PlatformProvider) sent.target()).identity());
        assertEquals("codewise-function-description", sent.sessionRef());
        assertEquals("deepseek-v4.1-flash", sent.modelName());
        assertEquals(4096, sent.maxTokens());
    }

    @Test
    void promptCarriesSignatureSolutionAndDataScaleButForbidsThirdPartyCopy() {
        FunctionDescriptionService service = service("{\"description\":\"题面正文\"}");
        FunctionDescriptionRequest request = request();
        request.setNotes("用哈希表记录下标");

        service.generate(request);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade).chat(captor.capture());
        String prompt = captor.getValue().prompt();
        assertTrue(prompt.contains("twoSum"), prompt);
        assertTrue(prompt.contains("\"name\":\"nums\""), prompt);
        assertTrue(prompt.contains("\"name\":\"target\""), prompt);
        assertTrue(prompt.contains("用哈希表记录下标"), prompt);
        assertTrue(prompt.contains("禁止复述"), prompt);
        // 数据规模必须写进提示词：题面里的约束要与平台随机生成的用例范围一致
        assertTrue(prompt.contains("节点数不超过 15"), prompt);
    }

    @Test
    void parsesStructuredResultAndStripsMarkdownFence() {
        FunctionDescriptionService service = service("""
                ```json
                {"description":"  重写后的题面  ","inputDesc":"输入说明","outputDesc":"输出说明",
                 "hint":"提示","difficulty":"中等","tags":"数组,哈希表"}
                ```
                """);

        FunctionDescriptionResponse response = service.generate(request());

        assertEquals("重写后的题面", response.getDescription());
        assertEquals("输入说明", response.getInputDesc());
        assertEquals("输出说明", response.getOutputDesc());
        assertEquals("提示", response.getHint());
        assertEquals(2, response.getDifficulty());
        assertEquals("数组,哈希表", response.getTags());
    }

    @Test
    void acceptsNumericDifficultyAndRejectsUnknownValue() {
        FunctionDescriptionService numeric = service("{\"description\":\"题面\",\"difficulty\":3}");
        assertEquals(3, numeric.generate(request()).getDifficulty());

        FunctionDescriptionService unknown = service("{\"description\":\"题面\",\"difficulty\":\"超难\"}");
        assertNull(unknown.generate(request()).getDifficulty());
    }

    @Test
    void rejectsMissingDescriptionInModelResult() {
        FunctionDescriptionService service = service("{\"inputDesc\":\"输入说明\"}");

        BizException exception = assertThrows(BizException.class, () -> service.generate(request()));
        assertEquals(AiErrorCode.DESCRIPTION_RESULT_INVALID.getCode(), exception.getCode());
    }

    @Test
    void rejectsUnparsableModelResult() {
        FunctionDescriptionService service = service("这不是 JSON");

        BizException exception = assertThrows(BizException.class, () -> service.generate(request()));
        assertEquals(AiErrorCode.AI_RESULT_PARSE_FAILED.getCode(), exception.getCode());
    }

    @Test
    void rejectsOverlongDescription() {
        when(aiFacade.chat(any(AiRequest.class)))
                .thenReturn("{\"description\":\"" + "长".repeat(20001) + "\"}");
        FunctionDescriptionService service = new FunctionDescriptionService(
                aiFacade, new ObjectMapper(), "opencode-go", "deepseek-v4.1-flash", "seed", 4096, 20000);

        BizException exception = assertThrows(BizException.class, () -> service.generate(request()));
        assertEquals(AiErrorCode.DESCRIPTION_RESULT_INVALID.getCode(), exception.getCode());
    }

    @Test
    void requiresSolutionCodeAndSignature() {
        FunctionDescriptionService service = new FunctionDescriptionService(
                aiFacade, new ObjectMapper(), "opencode-go", "deepseek-v4.1-flash", "seed", 4096, 20000);

        FunctionDescriptionRequest withoutSolution = request();
        withoutSolution.setSolutionCode(" ");
        BizException noSolution = assertThrows(BizException.class, () -> service.generate(withoutSolution));
        assertEquals(AiErrorCode.DESCRIPTION_SOLUTION_REQUIRED.getCode(), noSolution.getCode());

        FunctionDescriptionRequest withoutMethod = request();
        withoutMethod.setMethodName("");
        BizException noSignature = assertThrows(BizException.class, () -> service.generate(withoutMethod));
        assertEquals(AiErrorCode.FUNCTION_SIGNATURE_INCOMPLETE.getCode(), noSignature.getCode());
    }

    @Test
    void parsesParameterRangesAndDropsMalformedEntries() {
        FunctionDescriptionService service = service("""
                {"description":"题面","parameterRanges":[
                  {"name":"depth","min":1,"max":1000},
                  {"name":"  ","min":2},
                  {"name":"head","min":"zero","minSize":1,"maxSize":8},
                  {"name":"s","allowedChars":"123456789#"},
                  "not-an-object"
                ]}
                """);

        var ranges = service.generate(request()).getParameterRanges();

        assertEquals(3, ranges.size());
        assertEquals("depth", ranges.get(0).getName());
        assertEquals(1L, ranges.get(0).getMin());
        assertEquals(1000L, ranges.get(0).getMax());
        assertEquals("head", ranges.get(1).getName());
        assertNull(ranges.get(1).getMin());
        assertEquals(8, ranges.get(1).getMaxSize());
        assertEquals("123456789#", ranges.get(2).getAllowedChars());
    }

    @Test
    void promptAsksForParameterRanges() {
        FunctionDescriptionService service = service("{\"description\":\"题面\"}");

        service.generate(request());

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiFacade).chat(captor.capture());
        assertTrue(captor.getValue().prompt().contains("parameterRanges"), captor.getValue().prompt());
        assertTrue(captor.getValue().prompt().contains("取值域建议"), captor.getValue().prompt());
    }

    @Test
    void toleratesMissingOptionalSections() {
        FunctionDescriptionService service = service("{\"description\":\"题面正文\"}");
        FunctionDescriptionRequest request = request();
        request.setReferenceCode(null);
        request.setNotes("  ");

        assertNotNull(service.generate(request).getDescription());
    }

    private FunctionDescriptionRequest request() {
        FunctionDescriptionRequest request = new FunctionDescriptionRequest();
        request.setTitle("两数之和");
        request.setClassName("Solution");
        request.setMethodName("twoSum");
        request.setParameterConfig("[{\"type\":\"int[]\",\"name\":\"nums\"},{\"type\":\"int\",\"name\":\"target\"}]");
        request.setOutputType("int[]");
        request.setDifficulty(1);
        request.setTags("数组,哈希表");
        request.setSolutionCode("class Solution { public int[] twoSum(int[] nums, int target) { return new int[0]; } }");
        return request;
    }
}
