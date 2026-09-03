package org.example.servicequestion.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.entry.FunctionConfig;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.enums.QuestionType;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.vo.AgentQuestionDetailVo;
import org.example.servicequestion.vo.QuestionVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * getQuestionDetailsBatch（agent 批量题目详情）单元测试：
 * 逐题可见性判定（公开/私密本人/私密他人×管理员）、角色查询按批次去重、
 * 下架/审核中不可见、函数题携带核心配置、入参卫生。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionServiceAgentDetailTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private UserFeignClient userFeignClient;
    @Mock
    private FunctionConfigMapper functionConfigMapper;
    @InjectMocks
    private QuestionService questionService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Question question(long id, Integer status, Long creator, QuestionType type) {
        return Question.builder()
                .questionId(id)
                .title("题" + id)
                .status(status)
                .createUserId(creator)
                .questionType(type)
                .build();
    }

    private void stubUser(Integer roleId) {
        UserDto dto = new UserDto();
        dto.setRoleId(roleId);
        when(userFeignClient.getUserInfo(USER_ID)).thenReturn(Result.success(dto));
    }

    @Test
    void batchDetails_mixedVisibility() {
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(1, 1, OTHER_USER_ID, QuestionType.ACM),   // 公开题 → ok
                question(3, 3, OTHER_USER_ID, QuestionType.ACM)    // 他人私密题（非管理员）→ invisible
        ));
        stubUser(1);

        List<AgentQuestionDetailVo> results = questionService.getQuestionDetailsBatch(List.of(1L, 2L, 3L));

        assertEquals(3, results.size());
        assertEquals(AgentQuestionDetailVo.STATE_OK, results.get(0).getState());
        assertEquals("题1", results.get(0).getQuestion().getTitle());
        assertNull(results.get(0).getMessage());
        assertEquals(AgentQuestionDetailVo.STATE_NOT_FOUND, results.get(1).getState());
        assertEquals("题目不存在", results.get(1).getMessage());
        assertEquals(AgentQuestionDetailVo.STATE_INVISIBLE, results.get(2).getState());
        assertEquals("无权查看该题目", results.get(2).getMessage());
        assertNull(results.get(2).getQuestion());
    }

    @Test
    void batchDetails_ownPrivate_visibleWithoutRoleCheck() {
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(5, 3, USER_ID, QuestionType.ACM)
        ));

        List<AgentQuestionDetailVo> results = questionService.getQuestionDetailsBatch(List.of(5L));

        assertEquals(AgentQuestionDetailVo.STATE_OK, results.get(0).getState());
        verifyNoInteractions(userFeignClient);
    }

    @Test
    void batchDetails_foreignPrivate_adminVisible() {
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(5, 3, OTHER_USER_ID, QuestionType.ACM)
        ));
        stubUser(2);

        List<AgentQuestionDetailVo> results = questionService.getQuestionDetailsBatch(List.of(5L));

        assertEquals(AgentQuestionDetailVo.STATE_OK, results.get(0).getState());
        verify(userFeignClient, times(1)).getUserInfo(USER_ID);
    }

    @Test
    void batchDetails_roleCheckCalledOnceForMultiplePrivate() {
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(5, 3, OTHER_USER_ID, QuestionType.ACM),
                question(6, 3, OTHER_USER_ID, QuestionType.ACM)
        ));
        stubUser(1);

        List<AgentQuestionDetailVo> results = questionService.getQuestionDetailsBatch(List.of(5L, 6L));

        assertEquals(AgentQuestionDetailVo.STATE_INVISIBLE, results.get(0).getState());
        assertEquals(AgentQuestionDetailVo.STATE_INVISIBLE, results.get(1).getState());
        verify(userFeignClient, times(1)).getUserInfo(USER_ID);
    }

    @Test
    void batchDetails_takenDownOrReviewing_invisibleEvenWithoutRoleCheck() {
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(1, 0, OTHER_USER_ID, QuestionType.ACM),
                question(2, 2, OTHER_USER_ID, QuestionType.ACM)
        ));

        List<AgentQuestionDetailVo> results = questionService.getQuestionDetailsBatch(List.of(1L, 2L));

        assertEquals("题目已下架", results.get(0).getMessage());
        assertEquals("题目审核中", results.get(1).getMessage());
        verifyNoInteractions(userFeignClient);
    }

    @Test
    void batchDetails_acmType_skipsFunctionConfig() {
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(1, 1, USER_ID, QuestionType.ACM)
        ));

        questionService.getQuestionDetailsBatch(List.of(1L));

        verify(functionConfigMapper, never()).selectOne(any());
    }

    @Test
    void batchDetails_functionType_carriesFunctionConfig() {
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(1, 1, USER_ID, QuestionType.FUNCTION)
        ));
        FunctionConfig config = new FunctionConfig();
        when(functionConfigMapper.selectOne(any())).thenReturn(config);

        QuestionVo vo = questionService.getQuestionDetailsBatch(List.of(1L)).get(0).getQuestion();

        assertSame(config, vo.getFunctionConfig());
    }

    @Test
    void batchDetails_inputHygiene() {
        assertThrows(IllegalArgumentException.class, () -> questionService.getQuestionDetailsBatch(List.of()));
        assertThrows(IllegalArgumentException.class, () -> questionService.getQuestionDetailsBatch(null));
        assertThrows(IllegalArgumentException.class,
                () -> questionService.getQuestionDetailsBatch(LongStream.rangeClosed(1, 11).boxed().toList()));
        // null 与重复元素被过滤后不超限（Arrays.asList 允许 null，与真实 JSON 入参一致）
        when(questionMapper.selectList(any())).thenReturn(List.of());
        List<AgentQuestionDetailVo> results =
                questionService.getQuestionDetailsBatch(java.util.Arrays.asList(1L, 1L, null, 2L));
        assertEquals(2, results.size());
        assertEquals(AgentQuestionDetailVo.STATE_NOT_FOUND, results.get(0).getState());
    }
}
