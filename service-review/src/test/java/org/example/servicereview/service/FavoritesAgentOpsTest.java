package org.example.servicereview.service;

import org.example.serviceapi.dto.Result;
import org.example.serviceapi.dto.question.QuestionBriefDto;
import org.example.serviceapi.feign.QuestionFeignClient;
import org.example.servicecommon.until.UserContext;
import org.example.servicereview.dto.AgentFavoriteCreateDto;
import org.example.servicereview.dto.AgentFavoriteMoveDto;
import org.example.servicereview.dto.AgentFavoriteUpdateDto;
import org.example.servicereview.entry.Favorites;
import org.example.servicereview.mapper.FavoritesMapper;
import org.example.servicereview.vo.FavoriteAddResultVo;
import org.example.servicereview.vo.FavoriteFolderVo;
import org.example.servicereview.vo.FavoriteLocationVo;
import org.example.servicereview.vo.FavoriteMoveResultVo;
import org.example.servicereview.vo.FavoriteQuestionBriefVo;
import org.example.servicereview.vo.FavoriteRemoveResultVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FavoritesService agent 专用批量操作的单元测试：
 * 瘦身 VO 投影、懒删除、幂等创建、元信息更新、批量增删、行锁移动与定位。
 * 并发行锁本身（FOR UPDATE）依赖真实 DB，不在 Mockito 覆盖范围内，见最终汇报限制说明。
 */
class FavoritesAgentOpsTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long FOLDER_A = 101L;
    private static final Long FOLDER_B = 102L;

    private FavoritesMapper favoritesMapper;
    private QuestionFeignClient questionFeignClient;
    private FavoritesService favoritesService;

    @BeforeEach
    void setUp() {
        favoritesMapper = org.mockito.Mockito.mock(FavoritesMapper.class);
        questionFeignClient = org.mockito.Mockito.mock(QuestionFeignClient.class);
        RedisTemplate<String, Object> redisTemplate = org.mockito.Mockito.mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        favoritesService = new FavoritesService(favoritesMapper, questionFeignClient);
        ReflectionTestUtils.setField(favoritesService, "redisTemplate", redisTemplate);
        UserContext.setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------- fixtures ----------

    private Favorites folder(Long id, Long ownerId, List<Long> questionIds) {
        return Favorites.builder()
                .favoritesId(id)
                .favoritesName("夹" + id)
                .favoritesType("算法")
                .favoritesContent("描述" + id)
                .userId(ownerId)
                .questionIds(new ArrayList<>(questionIds))
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
    }

    private QuestionBriefDto brief(long questionId, Integer status, Long creator) {
        return QuestionBriefDto.builder()
                .questionId(questionId)
                .title("题" + questionId)
                .difficulty(1)
                .tags("dp")
                .status(status)
                .createUserId(creator)
                .totalSubmit(10L)
                .totalAc(5L)
                .passRate(BigDecimal.valueOf(50))
                .createTime(LocalDateTime.now())
                .build();
    }

    private void stubBrief(Result<List<QuestionBriefDto>> result) {
        when(questionFeignClient.getFavoritesBrief(org.mockito.ArgumentMatchers.anyList())).thenReturn(result);
    }

    // ---------- 瘦身列表 ----------

    @Test
    void listFavoriteFolderVos_returnsSlimVoWithCount() {
        when(favoritesMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                folder(FOLDER_A, USER_ID, List.of(1L, 2L, 3L)),
                folder(FOLDER_B, USER_ID, List.of())
        ));

        List<FavoriteFolderVo> vos = favoritesService.listFavoriteFolderVos();

        assertEquals(2, vos.size());
        FavoriteFolderVo a = vos.get(0);
        assertEquals(FOLDER_A, a.getFavoritesId());
        assertEquals("算法", a.getFavoritesType());
        assertEquals(3, a.getQuestionCount());
        FavoriteFolderVo b = vos.get(1);
        assertEquals(0, b.getQuestionCount());
        verifyNoInteractions(questionFeignClient);
    }

    @Test
    void listFavoriteFolderVos_unauthenticated_throws() {
        UserContext.clear();
        assertThrows(IllegalArgumentException.class, () -> favoritesService.listFavoriteFolderVos());
    }

    // ---------- 收藏夹题目瘦身列表（含懒删除） ----------

    @Test
    void getFavoriteQuestionBriefs_prunesInvisibleAndPersists() {
        Favorites f = folder(FOLDER_A, USER_ID, List.of(1L, 2L, 3L));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(f);
        // 与生产一致：Feign/Jackson 反序列化出的列表是可变 ArrayList（服务端会做懒删除回写）
        stubBrief(Result.success(new ArrayList<>(List.of(
                brief(1, 1, OTHER_USER_ID),   // 公开题 → 可见
                brief(2, 3, OTHER_USER_ID),   // 他人私密题 → 懒删除
                brief(3, 3, USER_ID)          // 本人私密题 → 可见
        ))));

        List<FavoriteQuestionBriefVo> briefs = favoritesService.getFavoriteQuestionBriefs(FOLDER_A);

        assertEquals(2, briefs.size());
        assertEquals(1L, briefs.get(0).getQuestionId());
        assertEquals(3L, briefs.get(1).getQuestionId());
        assertEquals("题1", briefs.get(0).getTitle());
        assertEquals(50, briefs.get(0).getPassRate().intValue());
        // 懒删除回写：2 被剔除
        assertEquals(List.of(1L, 3L), f.getQuestionIds());
        verify(favoritesMapper).updateById(f);
    }

    @Test
    void getFavoriteQuestionBriefs_emptyFolder_skipsFeign() {
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, USER_ID, List.of()));

        assertTrue(favoritesService.getFavoriteQuestionBriefs(FOLDER_A).isEmpty());
        verifyNoInteractions(questionFeignClient);
    }

    @Test
    void getFavoriteQuestionBriefs_notOwner_throws() {
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, OTHER_USER_ID, List.of(1L)));

        assertThrows(IllegalArgumentException.class, () -> favoritesService.getFavoriteQuestionBriefs(FOLDER_A));
        verifyNoInteractions(questionFeignClient);
    }

    // ---------- 创建 ----------

    @Test
    void createFavoritesAgent_success_returnsVoWithBackfilledId() {
        when(valueOps().setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("uuid-1"),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        doAnswer(invocation -> {
            Favorites saved = invocation.getArgument(0);
            saved.setFavoritesId(99L);
            return 1;
        }).when(favoritesMapper).insert(org.mockito.ArgumentMatchers.any(Favorites.class));

        AgentFavoriteCreateDto dto = new AgentFavoriteCreateDto();
        dto.setFavoritesName("  面经  ");
        dto.setFavoritesType(" 八股 ");
        dto.setFavoritesContent("描述");
        dto.setRequestId("uuid-1");

        FavoriteFolderVo vo = favoritesService.createFavoritesAgent(dto);

        assertEquals(99L, vo.getFavoritesId());
        assertEquals("面经", vo.getFavoritesName());
        assertEquals("八股", vo.getFavoritesType());
        assertEquals("描述", vo.getFavoritesContent());
        assertEquals(0, vo.getQuestionCount());
    }

    private ValueOperations<String, Object> valueOps() {
        RedisTemplate<String, Object> redisTemplate =
                (RedisTemplate<String, Object>) ReflectionTestUtils.getField(favoritesService, "redisTemplate");
        return redisTemplate.opsForValue();
    }

    @Test
    void createFavoritesAgent_duplicateRequestId_throws() {
        when(valueOps().setIfAbsent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any())).thenReturn(false);

        AgentFavoriteCreateDto dto = new AgentFavoriteCreateDto();
        dto.setFavoritesName("面经");
        dto.setRequestId("uuid-1");

        assertThrows(IllegalArgumentException.class, () -> favoritesService.createFavoritesAgent(dto));
        verify(favoritesMapper, never()).insert(org.mockito.ArgumentMatchers.any(Favorites.class));
    }

    @Test
    void createFavoritesAgent_blankNameOrRequestId_throws() {
        AgentFavoriteCreateDto noName = new AgentFavoriteCreateDto();
        noName.setRequestId("uuid-1");
        noName.setFavoritesName("   ");
        assertThrows(IllegalArgumentException.class, () -> favoritesService.createFavoritesAgent(noName));

        AgentFavoriteCreateDto noRequestId = new AgentFavoriteCreateDto();
        noRequestId.setFavoritesName("面经");
        assertThrows(IllegalArgumentException.class, () -> favoritesService.createFavoritesAgent(noRequestId));
    }

    // ---------- 更新元信息 ----------

    @Test
    void updateFavoritesMeta_updatesOnlyProvidedFields() {
        Favorites f = folder(FOLDER_A, USER_ID, List.of(5L, 6L));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(f);

        AgentFavoriteUpdateDto dto = new AgentFavoriteUpdateDto();
        dto.setFavoritesId(FOLDER_A);
        dto.setFavoritesName("新名");

        FavoriteFolderVo vo = favoritesService.updateFavoritesMeta(dto);

        assertEquals("新名", vo.getFavoritesName());
        assertEquals("算法", vo.getFavoritesType());
        assertEquals("描述" + FOLDER_A, vo.getFavoritesContent());
        assertEquals(2, vo.getQuestionCount());
        assertEquals(List.of(5L, 6L), f.getQuestionIds());
        verify(favoritesMapper).updateById(f);
    }

    @Test
    void updateFavoritesMeta_noFields_throws() {
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, USER_ID, List.of()));

        AgentFavoriteUpdateDto dto = new AgentFavoriteUpdateDto();
        dto.setFavoritesId(FOLDER_A);
        assertThrows(IllegalArgumentException.class, () -> favoritesService.updateFavoritesMeta(dto));
        verify(favoritesMapper, never()).updateById(org.mockito.ArgumentMatchers.any(Favorites.class));
    }

    // ---------- 批量添加 ----------

    @Test
    void batchAddQuestions_dedupesAndFilters() {
        Favorites f = folder(FOLDER_A, USER_ID, List.of(1L));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(f);
        stubBrief(Result.success(List.of(
                brief(2, 1, OTHER_USER_ID),   // 公开题 → 加入
                brief(3, 3, OTHER_USER_ID)    // 他人私密题 → 不可见跳过
        )));
        // 1=重复，2=加入，3=不可见，4=不存在
        FavoriteAddResultVo result = favoritesService.batchAddQuestions(FOLDER_A, List.of(1L, 2L, 3L, 4L, 2L));

        assertEquals(1, result.getAdded());
        assertEquals(List.of(1L), result.getSkippedDuplicateIds());
        assertEquals(List.of(3L), result.getSkippedInvisibleIds());
        assertEquals(List.of(4L), result.getInvalidQuestionIds());
        ArgumentCaptor<Favorites> captor = ArgumentCaptor.forClass(Favorites.class);
        verify(favoritesMapper).updateById(captor.capture());
        assertEquals(List.of(1L, 2L), captor.getValue().getQuestionIds());
    }

    @Test
    void batchAddQuestions_allDuplicates_skipsFeign() {
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, USER_ID, List.of(1L)));

        FavoriteAddResultVo result = favoritesService.batchAddQuestions(FOLDER_A, List.of(1L));

        assertEquals(0, result.getAdded());
        assertEquals(List.of(1L), result.getSkippedDuplicateIds());
        verifyNoInteractions(questionFeignClient);
        verify(favoritesMapper, never()).updateById(org.mockito.ArgumentMatchers.any(Favorites.class));
    }

    @Test
    void batchAddQuestions_overLimit_throws() {
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, USER_ID, List.of()));
        List<Long> tooMany = LongStream.rangeClosed(1, 51).boxed().toList();

        assertThrows(IllegalArgumentException.class, () -> favoritesService.batchAddQuestions(FOLDER_A, tooMany));
    }

    @Test
    void batchAddQuestions_notOwner_throws() {
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, OTHER_USER_ID, List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> favoritesService.batchAddQuestions(FOLDER_A, List.of(1L)));
    }

    // ---------- 批量移除 ----------

    @Test
    void batchRemoveQuestions_removesExistingReportsMissing() {
        Favorites f = folder(FOLDER_A, USER_ID, List.of(1L, 2L, 3L));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(f);

        FavoriteRemoveResultVo result = favoritesService.batchRemoveQuestions(FOLDER_A, List.of(2L, 3L, 9L));

        assertEquals(2, result.getRemoved());
        assertEquals(List.of(9L), result.getNotInFolderIds());
        ArgumentCaptor<Favorites> captor = ArgumentCaptor.forClass(Favorites.class);
        verify(favoritesMapper).updateById(captor.capture());
        assertEquals(List.of(1L), captor.getValue().getQuestionIds());
    }

    // ---------- 行锁移动 ----------

    @Test
    void moveQuestions_movesOnlyMovableAndLocksFirst() {
        Favorites from = folder(FOLDER_A, USER_ID, List.of(1L, 2L, 3L));
        Favorites to = folder(FOLDER_B, USER_ID, List.of(4L));
        when(favoritesMapper.lockByIdsForUpdate(FOLDER_A, FOLDER_B)).thenReturn(List.of(FOLDER_A, FOLDER_B));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(from);
        when(favoritesMapper.selectById(FOLDER_B)).thenReturn(to);

        FavoriteMoveResultVo result = favoritesService.moveQuestions(FOLDER_A, FOLDER_B, List.of(2L, 3L, 5L));

        verify(favoritesMapper).lockByIdsForUpdate(FOLDER_A, FOLDER_B);
        assertEquals(2, result.getMoved());
        assertEquals(List.of(5L), result.getNotInSourceIds());
        assertTrue(result.getAlreadyInTargetIds().isEmpty());
        assertEquals(List.of(1L), from.getQuestionIds());
        assertEquals(List.of(4L, 2L, 3L), to.getQuestionIds());
        verify(favoritesMapper).updateById(from);
        verify(favoritesMapper).updateById(to);
    }

    @Test
    void moveQuestions_idAlreadyInTarget_keptInSource() {
        Favorites from = folder(FOLDER_A, USER_ID, List.of(1L, 2L));
        Favorites to = folder(FOLDER_B, USER_ID, List.of(2L));
        when(favoritesMapper.lockByIdsForUpdate(FOLDER_A, FOLDER_B)).thenReturn(List.of(FOLDER_A, FOLDER_B));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(from);
        when(favoritesMapper.selectById(FOLDER_B)).thenReturn(to);

        FavoriteMoveResultVo result = favoritesService.moveQuestions(FOLDER_A, FOLDER_B, List.of(1L, 2L));

        assertEquals(1, result.getMoved());
        assertEquals(List.of(2L), result.getAlreadyInTargetIds());
        // ID=2 同时在两侧：保留在源夹不动，避免语义不清
        assertEquals(List.of(2L), from.getQuestionIds());
        assertEquals(List.of(2L, 1L), to.getQuestionIds());
    }

    @Test
    void moveQuestions_sameFolder_throwsWithoutLocking() {
        assertThrows(IllegalArgumentException.class,
                () -> favoritesService.moveQuestions(FOLDER_A, FOLDER_A, List.of(1L)));
        verify(favoritesMapper, never()).lockByIdsForUpdate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void moveQuestions_unknownFolder_throws() {
        when(favoritesMapper.lockByIdsForUpdate(FOLDER_A, FOLDER_B)).thenReturn(List.of(FOLDER_A));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, USER_ID, List.of(1L)));
        when(favoritesMapper.selectById(FOLDER_B)).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> favoritesService.moveQuestions(FOLDER_A, FOLDER_B, List.of(1L)));
        verify(favoritesMapper, never()).updateById(org.mockito.ArgumentMatchers.any(Favorites.class));
    }

    @Test
    void moveQuestions_foreignFolder_throws() {
        when(favoritesMapper.lockByIdsForUpdate(FOLDER_A, FOLDER_B)).thenReturn(List.of(FOLDER_A, FOLDER_B));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, USER_ID, List.of(1L)));
        when(favoritesMapper.selectById(FOLDER_B)).thenReturn(folder(FOLDER_B, OTHER_USER_ID, List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> favoritesService.moveQuestions(FOLDER_A, FOLDER_B, List.of(1L)));
        verify(favoritesMapper, never()).updateById(org.mockito.ArgumentMatchers.any(Favorites.class));
    }

    @Test
    void moveQuestions_emptyInput_throws() {
        when(favoritesMapper.lockByIdsForUpdate(FOLDER_A, FOLDER_B)).thenReturn(List.of(FOLDER_A, FOLDER_B));
        when(favoritesMapper.selectById(FOLDER_A)).thenReturn(folder(FOLDER_A, USER_ID, List.of(1L)));
        when(favoritesMapper.selectById(FOLDER_B)).thenReturn(folder(FOLDER_B, USER_ID, List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> favoritesService.moveQuestions(FOLDER_A, FOLDER_B, List.of()));
    }

    // ---------- 定位 ----------

    @Test
    void locateQuestions_mapsFoldersPerQuestion() {
        when(favoritesMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                folder(FOLDER_A, USER_ID, List.of(1L, 2L)),
                folder(FOLDER_B, USER_ID, List.of(2L, 3L))
        ));

        List<FavoriteLocationVo> locations = favoritesService.locateQuestions(List.of(2L, 9L));

        assertEquals(2, locations.size());
        assertEquals(2L, locations.get(0).getQuestionId());
        assertEquals(2, locations.get(0).getFolders().size());
        assertEquals(FOLDER_A, locations.get(0).getFolders().get(0).getFavoriteId());
        assertEquals("夹" + FOLDER_B, locations.get(0).getFolders().get(1).getFavoritesName());
        assertEquals(9L, locations.get(1).getQuestionId());
        assertTrue(locations.get(1).getFolders().isEmpty());
    }

    @Test
    void moveDto_carriesFields() {
        AgentFavoriteMoveDto dto = new AgentFavoriteMoveDto();
        dto.setFromFavoriteId(FOLDER_A);
        dto.setToFavoriteId(FOLDER_B);
        dto.setQuestionIds(List.of(1L));
        assertEquals(FOLDER_A, dto.getFromFavoriteId());
        assertEquals(FOLDER_B, dto.getToFavoriteId());
        assertEquals(List.of(1L), dto.getQuestionIds());
    }
}
