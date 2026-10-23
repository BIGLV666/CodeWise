package com.example.servicequiznest.service;

import com.example.servicequiznest.entity.GroupQuestion;
import com.example.servicequiznest.mapper.GroupMemberMapper;
import com.example.servicequiznest.mapper.GroupQuestionMapper;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 删组清题库：平台题只删索引，自建题才发 MQ；空组不产生任何写操作。
 */
@ExtendWith(MockitoExtension.class)
class GroupQuestionServiceCleanupTest {

    private static final long GROUP_ID = 88L;

    @Mock
    private QuestionFeignClient questionFeignClient;

    @Mock
    private GroupMemberMapper groupMemberMapper;

    @Mock
    private GroupQuestionMapper groupQuestionMapper;

    @Mock
    private GroupQuestionRollbackPublisher rollbackPublisher;

    @Mock
    private GroupQuestionCleanupPublisher cleanupPublisher;

    private GroupQuestionService groupQuestionService;

    @BeforeEach
    void setUp() {
        groupQuestionService = new GroupQuestionService(
                questionFeignClient, groupMemberMapper, groupQuestionMapper,
                rollbackPublisher, cleanupPublisher, "test-token");
    }

    @Test
    void emptyGroupSkipsDeleteAndEvent() {
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of());

        groupQuestionService.deleteGroupQuestions(GROUP_ID);

        verify(groupQuestionMapper, never()).delete(any());
        verifyNoInteractions(cleanupPublisher);
    }

    @Test
    void platformOnlyGroupDeletesIndexWithoutEvent() {
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(
                mapping(100L, 1), mapping(101L, 1)));

        groupQuestionService.deleteGroupQuestions(GROUP_ID);

        verify(groupQuestionMapper).delete(any());
        verifyNoInteractions(cleanupPublisher);
    }

    @Test
    void mixedGroupPublishesOnlyCustomQuestionIds() {
        when(groupQuestionMapper.selectList(any())).thenReturn(List.of(
                mapping(10L, 0), mapping(20L, 1), mapping(30L, 0)));

        groupQuestionService.deleteGroupQuestions(GROUP_ID);

        verify(groupQuestionMapper).delete(any());
        verify(cleanupPublisher).publish(GROUP_ID, List.of(10L, 30L));
    }

    @Test
    void cleanupRunsInDeletingGroupTransaction() throws NoSuchMethodException {
        Method method = GroupQuestionService.class.getMethod("deleteGroupQuestions", Long.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private static GroupQuestion mapping(long questionId, int isPlatFromQuestion) {
        return GroupQuestion.builder()
                .questionId(questionId)
                .groupId(GROUP_ID)
                .isPlatFromQuestion(isPlatFromQuestion)
                .build();
    }
}
