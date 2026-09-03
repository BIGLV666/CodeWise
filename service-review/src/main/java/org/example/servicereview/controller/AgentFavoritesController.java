package org.example.servicereview.controller;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.example.servicereview.dto.AgentFavoriteBatchAddDto;
import org.example.servicereview.dto.AgentFavoriteCreateDto;
import org.example.servicereview.dto.AgentFavoriteMoveDto;
import org.example.servicereview.dto.AgentFavoriteUpdateDto;
import org.example.servicereview.service.FavoritesService;
import org.example.servicereview.vo.FavoriteAddResultVo;
import org.example.servicereview.vo.FavoriteFolderVo;
import org.example.servicereview.vo.FavoriteLocationVo;
import org.example.servicereview.vo.FavoriteMoveResultVo;
import org.example.servicereview.vo.FavoriteQuestionBriefVo;
import org.example.servicereview.vo.FavoriteRemoveResultVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * codewise-agent 专用收藏夹接口（与网页端 {@link FavoritesController} 分离成类）。
 *
 * <p>差异点：</p>
 * <ul>
 *   <li>读接口返回瘦身 VO（收藏夹不含 userId/questionIds 明细；题目不含题干），
 *       降低 agent 会话的上下文与网络开销；</li>
 *   <li>写接口提供批量语义（批量增删、行锁移动），配合 agent 一次调用完成批量操作；</li>
 *   <li>创建接口直接返回含 favoritesId 的 VO，并复用 Redis requestId 幂等键。</li>
 * </ul>
 *
 * <p>身份安全：与网页端一致，userId 一律取自网关注入的 {@code UserContext}，
 * 不接受客户端提交的用户标识；题目/收藏归属校验在 service 层完成。</p>
 */
@RestController
@RequestMapping("/api/review/agent/favorites")
public class AgentFavoritesController {

    @Autowired
    private FavoritesService favoritesService;

    /**
     * 获取当前用户的所有收藏夹（瘦身 VO：名称/类型/简介/题目数量）。
     */
    @GetMapping
    @RateLimit(limit = 100, window = 60)
    public Result<List<FavoriteFolderVo>> listFavoriteFolders() {
        return Result.success(favoritesService.listFavoriteFolderVos());
    }

    /**
     * 获取指定收藏夹下的题目列表（瘦身：ID/标题/难度/标签/通过统计，不含题干）。
     */
    @GetMapping("/questions")
    @RateLimit(limit = 100, window = 60)
    public Result<List<FavoriteQuestionBriefVo>> getFavoriteQuestions(@RequestParam Long favoriteId) {
        return Result.success(favoritesService.getFavoriteQuestionBriefs(favoriteId));
    }

    /**
     * 定位题目所在收藏夹：返回每个题目被哪些收藏夹包含。
     */
    @GetMapping("/locate")
    @RateLimit(limit = 100, window = 60)
    public Result<List<FavoriteLocationVo>> locateQuestions(@RequestParam List<Long> questionIds) {
        return Result.success(favoritesService.locateQuestions(questionIds));
    }

    /**
     * 创建收藏夹：requestId 由 agent 客户端生成做幂等；返回含新 favoritesId 的瘦身 VO。
     */
    @PostMapping
    @RateLimit(limit = 20, window = 60)
    public Result<FavoriteFolderVo> createFavorite(@RequestBody AgentFavoriteCreateDto dto) {
        return Result.success(favoritesService.createFavoritesAgent(dto));
    }

    /**
     * 更新收藏夹元信息（名称/类型/简介）；不接收 questionIds，题目列表只能走批量接口。
     */
    @PutMapping
    @RateLimit(limit = 30, window = 60)
    public Result<FavoriteFolderVo> updateFavorite(@RequestBody AgentFavoriteUpdateDto dto) {
        return Result.success(favoritesService.updateFavoritesMeta(dto));
    }

    /**
     * 删除收藏夹（不可恢复；仅删除收藏关系，不影响题目本身）。
     */
    @DeleteMapping
    @RateLimit(limit = 30, window = 60)
    public Result<String> deleteFavorite(@RequestParam Long favoriteId) {
        return Result.success(favoritesService.deleteFavorites(favoriteId));
    }

    /**
     * 批量添加题目到收藏夹：自动去重，前置校验题目存在性与可见性，返回分项明细。
     */
    @PostMapping("/questions")
    @RateLimit(limit = 20, window = 60)
    public Result<FavoriteAddResultVo> batchAddQuestions(@RequestBody AgentFavoriteBatchAddDto dto) {
        return Result.success(favoritesService.batchAddQuestions(dto.getFavoriteId(), dto.getQuestionIds()));
    }

    /**
     * 从收藏夹批量移除题目：移除实际存在的题目，其余报告 notInFolderIds。
     */
    @DeleteMapping("/questions")
    @RateLimit(limit = 30, window = 60)
    public Result<FavoriteRemoveResultVo> batchRemoveQuestions(@RequestParam Long favoriteId,
                                                               @RequestParam List<Long> questionIds) {
        return Result.success(favoritesService.batchRemoveQuestions(favoriteId, questionIds));
    }

    /**
     * 跨收藏夹批量移动题目：事务内按主键升序对源/目标两行加行锁后读改写。
     */
    @PostMapping("/move")
    @RateLimit(limit = 30, window = 60)
    public Result<FavoriteMoveResultVo> moveQuestions(@RequestBody AgentFavoriteMoveDto dto) {
        return Result.success(favoritesService.moveQuestions(dto.getFromFavoriteId(), dto.getToFavoriteId(), dto.getQuestionIds()));
    }
}
