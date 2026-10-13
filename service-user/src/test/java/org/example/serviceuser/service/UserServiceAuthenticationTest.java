package org.example.serviceuser.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.serviceapi.exception.BizException;
import org.example.servicecommon.until.UserContext;
import org.example.serviceuser.dto.UserDto;
import org.example.serviceuser.entry.User;
import org.example.serviceuser.exception.UserErrorCode;
import org.example.serviceuser.mapper.UserMapper;
import org.example.serviceuser.until.JwtUntil;
import org.example.serviceuser.until.PasswordEncoding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceAuthenticationTest {
    private static final String PASSWORD = "correct-password";
    private static final String PASSWORD_HASH = PasswordEncoding.encode(PASSWORD);
    private static final String CODE = "123456";
    private static final String EMAIL_254 = "a".repeat(242) + "@example.com";

    @Mock private UserMapper userMapper;
    @Mock private JwtUntil jwtUntil;
    @Mock private UserMailService userMailService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @InjectMocks private UserService service;
    @Captor private ArgumentCaptor<Wrapper<User>> queries;
    @Captor private ArgumentCaptor<User> writtenUsers;

    enum EmailFlow {
        PASSWORD_LOGIN, LOGIN_CODE_SEND, CODE_LOGIN, PRE_REGISTER,
        ACTIVATE, RESET_CODE_SEND, RESET_PASSWORD
    }

    @BeforeEach
    void setIp() {
        UserContext.setCurrentIp("127.0.0.1");
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void usernameTakesPrecedenceEvenWhenInputLooksLikeAnotherAccountsEmail() {
        String account = "another@example.com";
        User usernameOwner = user("StoredName", 1);
        when(userMapper.selectOne(any())).thenReturn(usernameOwner);
        when(jwtUntil.generateToken(42L, "StoredName")).thenReturn("token");

        Map<String, Object> result = service.login(account, PASSWORD);

        assertEquals("token", result.get("token"));
        assertEquals("StoredName", ((UserDto) result.get("user")).getUserName());
        verify(userMapper).selectOne(queries.capture());
        assertQuery(queries.getValue(), "user_name", account);
        verify(userMapper).updateById(usernameOwner);
        verify(jwtUntil).generateToken(42L, "StoredName");
        assertEquals("127.0.0.1", usernameOwner.getLastLoginIp());
        assertNotNull(usernameOwner.getLastLoginTime());
        verifyNoInteractions(redisTemplate, userMailService, rabbitTemplate);
    }

    @Test
    void wrongUsernamePasswordNeverFallsBackToEmail() {
        when(userMapper.selectOne(any())).thenReturn(user("username-owner", 1));

        assertError(UserErrorCode.LOGIN_FAILED, () -> service.login("shared@example.com", "wrong"));

        verify(userMapper).selectOne(queries.capture());
        assertQuery(queries.getValue(), "user_name", "shared@example.com");
        verifyNoMoreInteractions(userMapper);
        verifyNoInteractions(jwtUntil, redisTemplate, userMailService, rabbitTemplate);
    }

    @Test
    void absentUsernameFallsBackToEmailAndUsesStoredUsernameInToken() {
        String account = " Mixed@Example.COM ";
        User emailOwner = user("canonical-name", 1);
        when(userMapper.selectOne(any())).thenReturn(null, emailOwner);
        when(jwtUntil.generateToken(42L, "canonical-name")).thenReturn("email-token");

        assertEquals("email-token", service.login(account, PASSWORD).get("token"));

        verify(userMapper, times(2)).selectOne(queries.capture());
        assertQuery(queries.getAllValues().get(0), "user_name", account);
        assertQuery(queries.getAllValues().get(1), "email", account);
        verify(userMapper).updateById(emailOwner);
        verify(jwtUntil).generateToken(42L, "canonical-name");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingAccountAndWrongEmailPasswordHaveSameGenericError(boolean emailExists) {
        when(userMapper.selectOne(any())).thenReturn(null, emailExists ? user("owner", 1) : null);

        BizException error = assertError(UserErrorCode.LOGIN_FAILED,
                () -> service.login("person@example.com", "wrong"));

        assertEquals(UserErrorCode.LOGIN_FAILED.getMessage(), error.getMessage());
        verify(userMapper, times(2)).selectOne(any());
        verifyNoMoreInteractions(userMapper);
        verifyNoInteractions(jwtUntil, redisTemplate, userMailService, rabbitTemplate);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usernameAndEmailFallbackBothEnforceBansBeforeLoginUpdates(boolean fallback) {
        User banned = user("banned-owner", 0);
        banned.setBanTime(LocalDateTime.of(2026, 10, 20, 12, 30));
        if (fallback) {
            when(userMapper.selectOne(any())).thenReturn(null, banned);
        } else {
            when(userMapper.selectOne(any())).thenReturn(banned);
        }

        BizException error = assertError(UserErrorCode.ACCOUNT_BANNED,
                () -> service.login("account", PASSWORD));

        assertTrue(error.getMessage().contains("2026-10-20 12:30"));
        verify(userMapper, times(fallback ? 2 : 1)).selectOne(any());
        verifyNoMoreInteractions(userMapper);
        verifyNoInteractions(jwtUntil, redisTemplate, userMailService, rabbitTemplate);
    }

    @Test
    void bannedAccountWithWrongPasswordStillReturnsGenericCredentialsError() {
        when(userMapper.selectOne(any())).thenReturn(user("banned", 0));
        assertError(UserErrorCode.LOGIN_FAILED, () -> service.login("banned", "wrong"));
        verifyNoInteractions(jwtUntil);
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void banWithoutEndTimeKeepsExistingMessage() {
        when(userMapper.selectOne(any())).thenReturn(null, user("banned", 0));
        BizException error = assertError(UserErrorCode.ACCOUNT_BANNED,
                () -> service.login("banned@example.com", PASSWORD));
        assertTrue(error.getMessage().contains("解封时间待定，请联系管理员"));
        verifyNoInteractions(jwtUntil);
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void nullStatusStillAllowsLogin() {
        when(userMapper.selectOne(any())).thenReturn(user("owner", null));
        service.login("owner", PASSWORD);
        verify(jwtUntil).generateToken(42L, "owner");
    }

    @Test
    void missingUsernameOrPasswordKeepsExistingValidationBeforeDependencies() {
        assertError(UserErrorCode.USERNAME_OR_PASSWORD_MISSING, () -> service.login(null, PASSWORD));
        assertError(UserErrorCode.USERNAME_OR_PASSWORD_MISSING, () -> service.login("owner", null));
        verifyNoInteractions(userMapper, jwtUntil, redisTemplate, userMailService, rabbitTemplate);
    }

    @Test
    void emailFallbackAccepts254Characters() {
        when(userMapper.selectOne(any())).thenReturn(null, user("owner", 1));
        service.login(EMAIL_254, PASSWORD);
        verify(userMapper, times(2)).selectOne(queries.capture());
        assertQuery(queries.getAllValues().get(1), "email", EMAIL_254);
        verify(jwtUntil).generateToken(42L, "owner");
    }

    @Test
    void emailFallbackRejects255BeforeEmailLookup() {
        String account = EMAIL_254 + "x";
        when(userMapper.selectOne(any())).thenReturn(null);
        assertLengthError(() -> service.login(account, PASSWORD));
        verify(userMapper).selectOne(queries.capture());
        assertQuery(queries.getValue(), "user_name", account);
        verifyNoMoreInteractions(userMapper);
        verifyNoInteractions(jwtUntil, redisTemplate, userMailService, rabbitTemplate);
    }

    @Test
    void emailLengthRuleDoesNotApplyToMatchedUsername() {
        String account = "x".repeat(255);
        when(userMapper.selectOne(any())).thenReturn(user("stored-name", 1));
        service.login(account, PASSWORD);
        verify(userMapper).selectOne(any());
        verify(jwtUntil).generateToken(42L, "stored-name");
    }

    @ParameterizedTest
    @EnumSource(EmailFlow.class)
    void allExplicitEmailFlowsReject255BeforeAnyDependency(EmailFlow flow) {
        assertLengthError(() -> invoke(flow, EMAIL_254 + "x"));
        verifyNoInteractions(userMapper, jwtUntil, redisTemplate, valueOperations, userMailService, rabbitTemplate);
    }

    @ParameterizedTest
    @EnumSource(EmailFlow.class)
    void allExplicitEmailFlowsAccept254AndPreserveOriginalEmail(EmailFlow flow) {
        assertSuccessfulFlowPreservesEmail(flow, EMAIL_254);
    }

    @ParameterizedTest
    @EnumSource(EmailFlow.class)
    void emailFlowsDoNotTrimNormalizeCaseOrAddFormatPolicy(EmailFlow flow) {
        assertSuccessfulFlowPreservesEmail(flow, " MixedCaseWithoutAt ");
    }

    @Test
    void activationRejectsOverlongCachedEmailBeforeInsert() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user_code:register--short@example.com"))
                .thenReturn(registrationPayload(EMAIL_254 + "x"));

        assertLengthError(() -> service.register("short@example.com", CODE));

        verify(valueOperations).get("user_code:register--short@example.com");
        verifyNoInteractions(userMapper, jwtUntil, userMailService, rabbitTemplate);
    }

    @Test
    void activationAccepts254CharacterCachedEmailEvenWithDifferentLookupKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("user_code:register--short@example.com"))
                .thenReturn(registrationPayload(EMAIL_254));
        service.register("short@example.com", CODE);
        verify(userMapper).insert(writtenUsers.capture());
        assertEquals(EMAIL_254, writtenUsers.getValue().getEmail());
    }

    @Test
    void explicitEmailLoginKeepsMissingInputErrors() {
        assertError(UserErrorCode.EMAIL_OR_PASSWORD_MISSING, () -> service.emailLogin(null, PASSWORD));
        assertError(UserErrorCode.EMAIL_OR_PASSWORD_MISSING, () -> service.emailLogin("email", null));
        assertError(UserErrorCode.EMAIL_MISSING, () -> service.getEmailCode(" "));
        verifyNoInteractions(userMapper, jwtUntil, redisTemplate, userMailService, rabbitTemplate);
    }

    private void assertSuccessfulFlowPreservesEmail(EmailFlow flow, String email) {
        User owner = user("canonical-name", 1);
        switch (flow) {
            case PASSWORD_LOGIN, LOGIN_CODE_SEND, RESET_CODE_SEND ->
                    when(userMapper.selectOne(any())).thenReturn(owner);
            case PRE_REGISTER -> when(userMapper.selectOne(any())).thenReturn(null);
            case CODE_LOGIN, RESET_PASSWORD -> when(userMapper.selectById(42L)).thenReturn(owner);
            case ACTIVATE -> { }
        }
        if (flow != EmailFlow.PASSWORD_LOGIN) {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }
        switch (flow) {
            case LOGIN_CODE_SEND, RESET_CODE_SEND, PRE_REGISTER ->
                    when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(60L), eq(TimeUnit.SECONDS)))
                            .thenReturn(true);
            case CODE_LOGIN -> when(valueOperations.get("user_code:login--" + email))
                    .thenReturn(Map.of("code", CODE, "userId", 42L));
            case RESET_PASSWORD -> {
                when(valueOperations.get("user_code:reset--" + email))
                        .thenReturn(Map.of("code", CODE, "userId", 42L));
                when(userMapper.updateById(owner)).thenReturn(1);
            }
            case ACTIVATE -> when(valueOperations.get("user_code:register--" + email))
                    .thenReturn(registrationPayload(email));
            case PASSWORD_LOGIN -> { }
        }

        assertDoesNotThrow(() -> invoke(flow, email));

        switch (flow) {
            case PASSWORD_LOGIN, LOGIN_CODE_SEND, RESET_CODE_SEND -> {
                verify(userMapper).selectOne(queries.capture());
                assertQuery(queries.getValue(), "email", email);
            }
            case PRE_REGISTER -> {
                verify(userMapper, times(2)).selectOne(queries.capture());
                assertQuery(queries.getAllValues().get(1), "email", email);
            }
            case CODE_LOGIN -> verify(valueOperations).get("user_code:login--" + email);
            case RESET_PASSWORD -> {
                verify(valueOperations).get("user_code:reset--" + email);
                verify(userMapper).updateById(owner);
                assertTrue(PasswordEncoding.matches(PASSWORD, owner.getPassword()));
            }
            case ACTIVATE -> {
                verify(valueOperations).get("user_code:register--" + email);
                verify(userMapper).insert(writtenUsers.capture());
                assertEquals(email, writtenUsers.getValue().getEmail());
            }
        }
        if (flow == EmailFlow.LOGIN_CODE_SEND || flow == EmailFlow.RESET_CODE_SEND || flow == EmailFlow.PRE_REGISTER) {
            verify(userMailService).sendCodeMail(eq(email), matches("\\d{6}"), eq(5L));
        }
        if (flow == EmailFlow.PASSWORD_LOGIN || flow == EmailFlow.CODE_LOGIN) {
            verify(jwtUntil).generateToken(42L, "canonical-name");
        }
    }

    private void invoke(EmailFlow flow, String email) {
        switch (flow) {
            case PASSWORD_LOGIN -> service.emailLogin(email, PASSWORD);
            case LOGIN_CODE_SEND -> service.getEmailCode(email);
            case CODE_LOGIN -> service.emailLoginForCode(email, CODE);
            case PRE_REGISTER -> service.email_regis(email, "new-user", PASSWORD);
            case ACTIVATE -> service.register(email, CODE);
            case RESET_CODE_SEND -> service.updatePasswordForEmail(email, null);
            case RESET_PASSWORD -> service.updatePasswordForCode(email, CODE, PASSWORD);
        }
    }

    private static User user(String username, Integer status) {
        return User.builder().userId(42L).userName(username).password(PASSWORD_HASH).status(status).build();
    }

    private static Map<String, Object> registrationPayload(String email) {
        return Map.of("code", CODE, "email", email, "userName", "new-user", "password", PASSWORD_HASH);
    }

    private static void assertQuery(Wrapper<User> query, String column, String value) {
        assertTrue(query.getSqlSegment().contains(column + " ="), query.getSqlSegment());
        assertEquals(Map.of("MPGENVAL1", value),
                ((com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>) query).getParamNameValuePairs());
    }

    private static BizException assertError(UserErrorCode code, org.junit.jupiter.api.function.Executable action) {
        BizException error = assertThrows(BizException.class, action);
        assertSame(code, error.getErrorCode());
        assertEquals(code.getCode(), error.getCode());
        return error;
    }

    private static void assertLengthError(org.junit.jupiter.api.function.Executable action) {
        BizException error = assertError(UserErrorCode.EMAIL_TOO_LONG, action);
        assertEquals("邮箱地址不能超过 254 个字符，请调整后重新提交", error.getMessage());
    }
}
