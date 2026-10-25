package org.example.servicequestion.service;

import io.github.biglv666.apigovernance.filter.FilterContext;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.user.UserDto;
import org.example.serviceapi.exception.BizException;
import org.example.serviceapi.exception.CommonErrorCode;
import org.example.serviceapi.exception.ErrorCode;
import org.example.serviceapi.feign.UserFeignClient;
import org.example.servicecommon.aop.AdminAuthAspect;
import org.example.servicecommon.aop.RequireAdmin;
import org.example.servicecommon.until.UserContext;
import org.example.servicequestion.controller.HtmlController;
import org.example.servicequestion.controller.QuestionController;
import org.example.servicequestion.dto.InsertQuestionDto;
import org.example.servicequestion.entry.Question;
import org.example.servicequestion.exception.QuestionErrorCode;
import org.example.servicequestion.mapper.FunctionConfigMapper;
import org.example.servicequestion.mapper.QuestionMapper;
import org.example.servicequestion.mapper.SubmitRecordMapper;
import org.example.servicequestion.vo.QuestionVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuestionServiceTest {

    private static final Long QUESTION_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;

    @Mock private QuestionMapper questionMapper;
    @Mock private RedissonClient redissonClient;
    @Mock private SubmitRecordMapper submitRecordMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private UserFeignClient userFeignClient;
    @Mock private FunctionConfigMapper functionConfigMapper;
    @Mock private GroupQuestionPermissionService groupQuestionPermissionService;
    @InjectMocks private QuestionService questionService;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @ParameterizedTest
    @CsvSource({"true,10,1", "false,10,1", "true,20,1", "false,20,1",
            "true,10,-1", "false,10,3"})
    void mutationsRejectOrdinaryCreatorAndOtherNonAdminRoles(boolean update, long userId, int role) {
        UserContext.setUserId(userId);
        stubUser(userId, role);
        assertBusinessError(CommonErrorCode.ADMIN_ONLY, () -> mutate(update));
        verifyNoInteractions(questionMapper);
    }

    @ParameterizedTest
    @CsvSource({"true,2", "false,2", "true,0", "false,0"})
    void mutationsAllowAdminAndRootForOthersQuestion(boolean update, int role) {
        UserContext.setUserId(OTHER_USER_ID);
        stubUser(OTHER_USER_ID, role);
        Question question = stubQuestion();
        if (update) {
            when(questionMapper.updateById(question)).thenReturn(1);
            Question result = questionService.updateQuestion(validDto(), QUESTION_ID);
            assertSame(question, result);
            assertEquals("标题", result.getTitle());
            assertEquals("题目描述", result.getDescription());
            assertEquals(1000, result.getTimeLimit());
            assertEquals(256, result.getMemoryLimit());
            assertEquals(OWNER_ID, result.getCreateUserId());
            verify(questionMapper).updateById(question);
        } else {
            when(questionMapper.deleteById(QUESTION_ID)).thenReturn(1);
            assertDoesNotThrow(() -> questionService.deleteQuestion(QUESTION_ID));
            verify(questionMapper).deleteById(QUESTION_ID);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mutationsRejectAnonymousBeforeFeignOrDatabase(boolean update) {
        assertBusinessError(CommonErrorCode.LOGIN_REQUIRED, () -> mutate(update));
        verifyNoInteractions(userFeignClient, questionMapper);
    }

    @Test
    void getQuestionByIdAllowsVisiblePrivateQuestion() {
        UserContext.setUserId(OTHER_USER_ID);
        Question question = privateQuestion();
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question);
        when(groupQuestionPermissionService.canAccessPrivateQuestion(question)).thenReturn(true);

        QuestionVo result = questionService.getQuestionById(QUESTION_ID);

        assertEquals(QUESTION_ID, result.getQuestionId());
        verify(groupQuestionPermissionService).canAccessPrivateQuestion(question);
    }

    @Test
    void getQuestionByIdRejectsInvisiblePrivateQuestion() {
        UserContext.setUserId(OTHER_USER_ID);
        Question question = privateQuestion();
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question);
        when(groupQuestionPermissionService.canAccessPrivateQuestion(question)).thenReturn(false);

        assertBusinessError(CommonErrorCode.CONTENT_RESTRICTED,
                () -> questionService.getQuestionById(QUESTION_ID));
    }

    @Test
    void getQuestionByIdPropagatesGroupPermissionDownstreamFailure() {
        UserContext.setUserId(OTHER_USER_ID);
        Question question = privateQuestion();
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question);
        when(groupQuestionPermissionService.canAccessPrivateQuestion(question))
                .thenThrow(new BizException(CommonErrorCode.DOWNSTREAM_UNAVAILABLE));

        assertBusinessError(CommonErrorCode.DOWNSTREAM_UNAVAILABLE,
                () -> questionService.getQuestionById(QUESTION_ID));
    }

    @Test
    void getQuestionByIdDoesNotCheckGroupPermissionForPublicQuestion() {
        UserContext.setUserId(OTHER_USER_ID);
        Question question = privateQuestion();
        question.setStatus(1);
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question);

        QuestionVo result = questionService.getQuestionById(QUESTION_ID);

        assertEquals(QUESTION_ID, result.getQuestionId());
        verifyNoInteractions(groupQuestionPermissionService);
    }

    @ParameterizedTest
    @CsvSource({"true,nullResult", "false,nullResult", "true,errorCode", "false,errorCode",
            "true,nullCode", "false,nullCode", "true,nullData", "false,nullData",
            "true,nullRole", "false,nullRole"})
    void mutationsFailClosedForIncompleteOrFailedUserResponse(boolean update, String scenario) {
        UserContext.setUserId(OWNER_ID);
        UserDto user = new UserDto();
        user.setRoleId(2);
        Result<UserDto> response = Result.success(user);
        ErrorCode expected = CommonErrorCode.DOWNSTREAM_UNAVAILABLE;
        switch (scenario) {
            case "nullResult" -> response = null;
            // A failure response with privileged data must still be rejected.
            case "errorCode" -> response.setCode(500);
            case "nullCode" -> response.setCode(null);
            case "nullData" -> {
                response.setData(null);
                expected = QuestionErrorCode.USER_NOT_FOUND;
            }
            case "nullRole" -> {
                user.setRoleId(null);
                expected = CommonErrorCode.ADMIN_ONLY;
            }
            default -> fail("Unknown scenario");
        }
        when(userFeignClient.getUserInfo(OWNER_ID)).thenReturn(response);
        assertBusinessError(expected, () -> mutate(update));
        verifyNoInteractions(questionMapper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mutationsHideFeignFailureDetailsAndPreserveCause(boolean update) {
        UserContext.setUserId(OWNER_ID);
        RuntimeException cause = new RuntimeException("downstream secret");
        when(userFeignClient.getUserInfo(OWNER_ID)).thenThrow(cause);
        BizException error = assertThrows(BizException.class, () -> mutate(update));
        assertEquals(CommonErrorCode.DOWNSTREAM_UNAVAILABLE.getCode(), error.getCode());
        assertEquals(CommonErrorCode.DOWNSTREAM_UNAVAILABLE.getMessage(), error.getMessage());
        assertSame(cause, error.getCause());
        verifyNoInteractions(questionMapper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mutationsRejectMissingQuestionForAdmin(boolean update) {
        UserContext.setUserId(OWNER_ID);
        stubUser(OWNER_ID, 2);
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(null);
        assertBusinessError(QuestionErrorCode.QUESTION_NOT_FOUND, () -> mutate(update));
        verify(questionMapper, never()).updateById(any(Question.class));
        verify(questionMapper, never()).deleteById(QUESTION_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mutationsReportFailedDatabaseWrite(boolean update) {
        UserContext.setUserId(OTHER_USER_ID);
        stubUser(OTHER_USER_ID, 0);
        stubQuestion();
        assertBusinessError(update ? QuestionErrorCode.QUESTION_UPDATE_FAILED
                : QuestionErrorCode.QUESTION_DELETE_FAILED, () -> mutate(update));
    }

    private record InvalidInput(String name, Consumer<InsertQuestionDto> change, ErrorCode expected) {
        @Override public String toString() { return name; }
    }

    static Stream<InvalidInput> invalidInputs() {
        return Stream.of(
                new InvalidInput("null title", dto -> dto.setTitle(null), QuestionErrorCode.QUESTION_TITLE_REQUIRED),
                new InvalidInput("empty title", dto -> dto.setTitle(""), QuestionErrorCode.QUESTION_TITLE_REQUIRED),
                new InvalidInput("blank title", dto -> dto.setTitle(" \t\n"), QuestionErrorCode.QUESTION_TITLE_REQUIRED),
                new InvalidInput("null description", dto -> dto.setDescription(null), QuestionErrorCode.QUESTION_CONTENT_REQUIRED),
                new InvalidInput("empty description", dto -> dto.setDescription(""), QuestionErrorCode.QUESTION_CONTENT_REQUIRED),
                new InvalidInput("blank description", dto -> dto.setDescription(" \t\n"), QuestionErrorCode.QUESTION_CONTENT_REQUIRED),
                new InvalidInput("long title", dto -> dto.setTitle("题".repeat(201)), QuestionErrorCode.QUESTION_TITLE_TOO_LONG),
                new InvalidInput("long supplementary title", dto -> dto.setTitle("\uD840\uDC00".repeat(201)), QuestionErrorCode.QUESTION_TITLE_TOO_LONG),
                new InvalidInput("long tags", dto -> dto.setTags("签".repeat(256)), QuestionErrorCode.QUESTION_TAGS_TOO_LONG),
                new InvalidInput("long input", dto -> dto.setInputDesc("a".repeat(65_536)), QuestionErrorCode.QUESTION_TEXT_TOO_LONG),
                new InvalidInput("long output", dto -> dto.setOutputDesc("中".repeat(21_846)), QuestionErrorCode.QUESTION_TEXT_TOO_LONG),
                new InvalidInput("long sample input", dto -> dto.setSampleInput("a".repeat(65_536)), QuestionErrorCode.QUESTION_TEXT_TOO_LONG),
                new InvalidInput("long sample output", dto -> dto.setSampleOutput("\uD840\uDC00".repeat(16_384)), QuestionErrorCode.QUESTION_TEXT_TOO_LONG),
                new InvalidInput("long hint", dto -> dto.setHint("a".repeat(65_536)), QuestionErrorCode.QUESTION_TEXT_TOO_LONG),
                new InvalidInput("null time", dto -> dto.setTimeLimit(null), QuestionErrorCode.QUESTION_TIME_LIMIT_INVALID),
                new InvalidInput("zero time", dto -> dto.setTimeLimit(0), QuestionErrorCode.QUESTION_TIME_LIMIT_INVALID),
                new InvalidInput("negative time", dto -> dto.setTimeLimit(-1), QuestionErrorCode.QUESTION_TIME_LIMIT_INVALID),
                new InvalidInput("null memory", dto -> dto.setMemoryLimit(null), QuestionErrorCode.QUESTION_MEMORY_LIMIT_INVALID),
                new InvalidInput("zero memory", dto -> dto.setMemoryLimit(0), QuestionErrorCode.QUESTION_MEMORY_LIMIT_INVALID),
                new InvalidInput("negative memory", dto -> dto.setMemoryLimit(-1), QuestionErrorCode.QUESTION_MEMORY_LIMIT_INVALID));
    }

    @ParameterizedTest(name = "add rejects {0}")
    @MethodSource("invalidInputs")
    void addRejectsInvalidInputBeforeLockAndDatabase(InvalidInput input) {
        UserContext.setUserId(OWNER_ID);
        InsertQuestionDto dto = validDto();
        input.change().accept(dto);
        assertBusinessError(input.expected(), () -> questionService.addQuestion(dto));
        verifyNoInteractions(questionMapper, redissonClient);
    }

    @ParameterizedTest(name = "update rejects {0}")
    @MethodSource("invalidInputs")
    void updateRejectsInvalidInputBeforeDatabase(InvalidInput input) {
        UserContext.setUserId(OTHER_USER_ID);
        stubUser(OTHER_USER_ID, 2);
        InsertQuestionDto dto = validDto();
        input.change().accept(dto);
        assertBusinessError(input.expected(), () -> questionService.updateQuestion(dto, QUESTION_ID));
        verifyNoInteractions(questionMapper);
    }

    @Test
    void addRejectsAnonymousBeforeValidationAndLock() {
        assertBusinessError(CommonErrorCode.LOGIN_REQUIRED, () -> questionService.addQuestion(null));
        verifyNoInteractions(questionMapper, redissonClient);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void writesRejectNullDtoWithoutNullPointerException(boolean update) {
        UserContext.setUserId(OWNER_ID);
        if (update) stubUser(OWNER_ID, 2);
        assertBusinessError(CommonErrorCode.PARAM_MISSING, () -> {
            if (update) questionService.updateQuestion(null, QUESTION_ID);
            else questionService.addQuestion(null);
        });
        verifyNoInteractions(questionMapper, redissonClient);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void writesAcceptSqlLengthBoundariesAndPositiveIntegerLimits(boolean update, boolean boundary) throws Exception {
        UserContext.setUserId(OWNER_ID);
        InsertQuestionDto dto = validDto();
        if (boundary) {
            dto.setTitle("\uD840\uDC00".repeat(200));
            dto.setTags("\uD840\uDC00".repeat(255));
            dto.setInputDesc("a".repeat(65_535));
            dto.setOutputDesc("中".repeat(21_845));
            dto.setSampleInput("a".repeat(65_535));
            dto.setSampleOutput("\uD840\uDC00".repeat(16_383) + "abc");
            dto.setHint("中".repeat(21_845));
            dto.setDescription("文".repeat(30_000)); // LONGTEXT is not limited to TEXT capacity.
            dto.setTimeLimit(Integer.MAX_VALUE);
            dto.setMemoryLimit(1);
        }
        Question result;
        if (update) {
            stubUser(OWNER_ID, 2);
            stubQuestion();
            when(questionMapper.updateById(any(Question.class))).thenReturn(1);
            result = questionService.updateQuestion(dto, QUESTION_ID);
        } else {
            RLock lock = mock(RLock.class);
            when(redissonClient.getLock("lock_add_question" + OWNER_ID)).thenReturn(lock);
            when(lock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(questionMapper.insert(any(Question.class))).thenReturn(1);
            result = questionService.addQuestion(dto);
            verify(lock).unlock();
            assertEquals(3, result.getStatus());
            assertNotNull(result.getContentHash());
        }
        assertEquals(dto.getTitle(), result.getTitle());
        assertEquals(dto.getDescription(), result.getDescription());
        assertEquals(dto.getTags(), result.getTags());
        assertEquals(dto.getInputDesc(), result.getInputDesc());
        assertEquals(dto.getOutputDesc(), result.getOutputDesc());
        assertEquals(dto.getSampleInput(), result.getSampleInput());
        assertEquals(dto.getSampleOutput(), result.getSampleOutput());
        assertEquals(dto.getHint(), result.getHint());
        assertEquals(dto.getTimeLimit(), result.getTimeLimit());
        assertEquals(dto.getMemoryLimit(), result.getMemoryLimit());
    }

    static Stream<Method> adminEndpoints() throws NoSuchMethodException {
        return Stream.of(
                QuestionController.class.getDeclaredMethod("addQuestion", InsertQuestionDto.class),
                QuestionController.class.getDeclaredMethod("updateQuestion", InsertQuestionDto.class, Long.class),
                QuestionController.class.getDeclaredMethod("deleteQuestion", Long.class),
                HtmlController.class.getDeclaredMethod("html", MultipartFile.class));
    }

    @ParameterizedTest
    @MethodSource("adminEndpoints")
    void writeEndpointsRetainAdminAnnotationAndRejectAnonymous(Method method) {
        assertNotNull(method.getAnnotation(RequireAdmin.class));
        FilterContext context = filterContext(method);
        assertFalse(newAdminFilter().doFilter(context));
        verify(context).setRejectStatus(401);
        verify(context).setRejectReason(CommonErrorCode.LOGIN_REQUIRED.getMessage());
        verifyNoInteractions(userFeignClient);
    }

    @ParameterizedTest
    @MethodSource("adminEndpoints")
    void writeEndpointFiltersRejectOrdinaryAndNullRolesAndAllowAdminAndRoot(Method method) {
        UserContext.setUserId(OWNER_ID);
        for (Integer role : new Integer[]{1, null, 2, 0}) {
            stubUser(OWNER_ID, role);
            FilterContext context = filterContext(method);
            if (role == null || role == 1) {
                assertFalse(newAdminFilter().doFilter(context));
                verify(context).setRejectStatus(403);
                verify(context).setRejectReason(CommonErrorCode.ADMIN_ONLY.getMessage());
            } else {
                assertTrue(newAdminFilter().doFilter(context));
                verify(context, never()).setRejectStatus(anyInt());
                verify(context, never()).setRejectReason(anyString());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("adminEndpoints")
    void writeEndpointFiltersRejectMissingUserResponses(Method method) {
        UserContext.setUserId(OWNER_ID);
        for (Result<UserDto> response : java.util.Arrays.asList(null, Result.<UserDto>success((UserDto) null))) {
            when(userFeignClient.getUserInfo(OWNER_ID)).thenReturn(response);
            FilterContext context = filterContext(method);
            assertFalse(newAdminFilter().doFilter(context));
            verify(context).setRejectStatus(401);
            verify(context).setRejectReason(CommonErrorCode.LOGIN_REQUIRED.getMessage());
        }
    }

    private AdminAuthAspect newAdminFilter() {
        AdminAuthAspect filter = new AdminAuthAspect();
        ReflectionTestUtils.setField(filter, "userFeignClient", userFeignClient);
        return filter;
    }

    private FilterContext filterContext(Method method) {
        FilterContext context = mock(FilterContext.class);
        when(context.getMethod()).thenReturn(method);
        return context;
    }

    private void mutate(boolean update) {
        if (update) questionService.updateQuestion(validDto(), QUESTION_ID);
        else questionService.deleteQuestion(QUESTION_ID);
    }

    private void assertBusinessError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        BizException error = assertThrows(BizException.class, action);
        assertEquals(expected.getCode(), error.getCode());
        assertFalse(error.getMessage().isBlank());
    }

    private InsertQuestionDto validDto() {
        InsertQuestionDto dto = new InsertQuestionDto();
        dto.setTitle("标题");
        dto.setDescription("题目描述");
        dto.setTimeLimit(1000);
        dto.setMemoryLimit(256);
        return dto;
    }

    private void stubUser(Long userId, Integer roleId) {
        UserDto user = new UserDto();
        user.setUserId(userId.toString());
        user.setRoleId(roleId);
        user.setStatus(1);
        when(userFeignClient.getUserInfo(userId)).thenReturn(Result.success(user));
    }

    private Question stubQuestion() {
        Question question = Question.builder().questionId(QUESTION_ID).createUserId(OWNER_ID).build();
        when(questionMapper.selectById(QUESTION_ID)).thenReturn(question);
        return question;
    }

    private Question privateQuestion() {
        return Question.builder()
                .questionId(QUESTION_ID)
                .status(3)
                .createUserId(OWNER_ID)
                .questionType(org.example.servicequestion.enums.QuestionType.ACM)
                .build();
    }
}
