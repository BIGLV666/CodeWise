package org.example.servicereview.controller;

import org.example.serviceapi.dto.question.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.servicereview.dto.ReceiveDto;
import org.example.servicereview.entry.Favorites;
import org.example.servicereview.service.FavoritesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/review/favorites")
public class FavoritesController {

    @Autowired
    private FavoritesService favoritesService;

    /**
     * 获取当前用户的所有收藏夹
     */
    @GetMapping("/list")
    public Result<List<Favorites>> getAllFavorites() {
        List<Favorites> favorites = favoritesService.getAllFavoritesByUserId();
        return Result.success(favorites);
    }

    /**
     * 获取指定收藏夹下的所有问题
     */
    @GetMapping("/questions")
    public Result<List<QuestionDto>> getQuestionsByFavoriteId(@RequestParam Long favoriteId) {
        List<QuestionDto> questionDtos = favoritesService.getAllFavoritesByUserId(favoriteId);
        return Result.success(questionDtos);
    }

    /**
     * 获取用于创建收藏夹的请求ID，防止重复提交
     */
    @GetMapping("/requestId")
    public Result<String> getRequestId() {
        String requestId = favoritesService.getRequestId();
        return Result.success(requestId);
    }

    /**
     * 创建收藏夹
     */
    @PostMapping
    public Result<String> createFavorites(@RequestParam String favoritesName, @RequestParam String requestId) {
        favoritesService.createFavorites(favoritesName, requestId);
        return Result.success("创建成功");
    }

    /**
     * 向收藏夹中添加问题
     */
    @PostMapping("/question")
    public Result<String> insertQuestionId(@RequestParam Long questionId, @RequestParam Long favoriteId) {
        String result = favoritesService.insertQuestionId(questionId, favoriteId);
        return Result.success(result);
    }

    /**
     * 从收藏夹中删除问题
     */
    @DeleteMapping("/question")
    public Result<String> deleteQuestionId(@RequestParam Long questionId, @RequestParam Long favoriteId) {
        String result = favoritesService.deleteQuestionId(questionId, favoriteId);
        return Result.success(result);
    }

    /**
     * 删除收藏夹
     */
    @DeleteMapping
    public Result<String> deleteFavorites(@RequestParam Long favoriteId) {
        String result = favoritesService.deleteFavorites(favoriteId);
        return Result.success(result);
    }

    /**
     * 更新收藏夹信息
     */
    @PutMapping
    public Result<String> updateFavorites(@RequestBody ReceiveDto receiveDto) {
        String result = favoritesService.updateFavorites(receiveDto);
        return Result.success(result);
    }
}
