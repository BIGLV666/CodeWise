package org.example.servicequestion.service;

import org.example.servicequestion.mapper.QuestionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 批量清理题目：分片大小、空入参与幂等返回。 */
@ExtendWith(MockitoExtension.class)
class QuestionServiceDeleteQuestionsBatchTest {

    @Mock
    private QuestionMapper questionMapper;

    @InjectMocks
    private QuestionService questionService;

    @Test
    void emptyAndNullInputsDoNothing() {
        assertThat(questionService.deleteQuestionsBatch(null)).isZero();
        assertThat(questionService.deleteQuestionsBatch(List.of())).isZero();
        verifyNoInteractions(questionMapper);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void largeListIsDeletedInChunksOfFiveHundred() {
        List<Long> ids = IntStream.rangeClosed(1, 1200).mapToObj(Long::valueOf).toList();
        when(questionMapper.deleteBatchIds(anyCollection())).thenAnswer(
                invocation -> ((Collection<?>) invocation.getArgument(0)).size());

        int deleted = questionService.deleteQuestionsBatch(ids);

        assertThat(deleted).isEqualTo(1200);
        ArgumentCaptor<Collection<? extends Serializable>> captor =
                (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
        verify(questionMapper, times(3)).deleteBatchIds(captor.capture());
        assertThat(captor.getAllValues()).extracting(Collection::size)
                .containsExactly(500, 500, 200);
    }

    @Test
    void missingRowsAreTreatedAsAlreadyDeleted() {
        when(questionMapper.deleteBatchIds(anyCollection())).thenReturn(0);

        assertThat(questionService.deleteQuestionsBatch(List.of(1L, 2L))).isZero();

        verify(questionMapper).deleteBatchIds(anyCollection());
    }
}
