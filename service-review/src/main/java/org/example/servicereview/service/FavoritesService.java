package org.example.servicereview.service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.example.serviceapi.dto.QuestionDto;
import org.example.serviceapi.dto.Result;
import org.example.serviceapi.feign.QuestionFeignClient;

import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;

import org.example.servicereview.dto.ReceiveDto;
import org.example.servicereview.entry.Favorites;
import org.example.servicereview.mapper.FavoritesMapper;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.UUID;
import java.time.LocalDateTime;

@Service
public class FavoritesService {
    @Autowired
    private RedisTemplate<String,Object> redisTemplate;

    public FavoritesService(FavoritesMapper favoritesMapper,QuestionFeignClient questionFeignClient) {
        this.favoritesMapper = favoritesMapper;
        this.questionFeignClient = questionFeignClient;
    }

    private final FavoritesMapper favoritesMapper;

    private QuestionFeignClient questionFeignClient;


    public List<Favorites> getAllFavoritesByUserId() {
        return favoritesMapper.selectList(new QueryWrapper<Favorites>().eq("user_id",UserContext.getUserId()));
    }
    /**
     * 获取收藏下的所有问题
     * 同时懒删除不存在的问题
     * @param favoriteId
     * @return
     */
    public List<QuestionDto> getAllFavoritesByUserId(Long favoriteId) {
       Favorites favorites = favoritesMapper.selectById(favoriteId);
       if(favorites == null) {
        throw new IllegalArgumentException("未找到该收藏");
       }
       if(!favorites.getUserId().equals(UserContext.getUserId())) {
        throw new IllegalArgumentException("该收藏不是您的收藏");
       }
       List<Long> questionIds = favorites.getQuestionIds();
       if(questionIds.isEmpty()) {
        return Collections.emptyList();
       }
       Result<List<QuestionDto>> questionDtoBodys= questionFeignClient.getFavorites(questionIds);
       if(!questionDtoBodys.getCode().equals(200)) {
           throw new RuntimeException(questionDtoBodys.getMessage());
       }
       // 懒删除不存在的问题
       List<QuestionDto> questionDtos = questionDtoBodys.getData();
       if(questionDtos.isEmpty()) {
         return questionDtos;   
       }
       List<QuestionDto>remove=new ArrayList<>();
       for(QuestionDto questionDto:questionDtos) {
            if(questionDto.getStatus().equals(1)){
                remove.add(questionDto);
            }
        }

      
       if(!remove.isEmpty()){
            questionDtos.removeAll(remove);
            questionIds.removeAll(remove.stream().map(QuestionDto::getQuestionId).toList());
            favorites.setQuestionIds(questionIds);
            favoritesMapper.updateById(favorites);
       }

       return questionDtos;
    }
    /**
     * 创建收藏
     * 首先前端获取一个请求ID，请求ID用于判断是否重复创建
     * @param favoritesName
     * @param requestId
     */
    public void createFavorites(String favoritesName,String requestId){
        if(Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY+requestId,requestId,3, TimeUnit.MINUTES))) {
            throw new IllegalArgumentException("该收藏已创建");
        }
        Favorites favorites = Favorites.builder()
                .favoritesName(favoritesName)
                .questionIds(Collections.emptyList())
                .userId(UserContext.getUserId())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        favoritesMapper.insert(favorites);
    }
    /**
     * 获取请求ID
     * @return
     */
    public String getRequestId() {
        String requestId = UUID.randomUUID().toString();
      

        return requestId;
    }
    /**
     * 插入问题ID到收藏
     * @param questionId
     * @param favoriteId
     * @return
     */
    public String insertQuestionId(Long questionId,Long favoriteId) {
        Favorites favorites = favoritesMapper.selectById(favoriteId);
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        if(favorites.getQuestionIds().contains(questionId)) {
            throw new IllegalArgumentException("该收藏已存在该问题");
        }
        Result<QuestionDto> questionDtoBody = questionFeignClient.getQuestionInfo(questionId);
        if(!questionDtoBody.getCode().equals(200)) {
            throw new RuntimeException(questionDtoBody.getMessage());
        }
        QuestionDto questionDto = questionDtoBody.getData();
        List<Long>questionIds=favorites.getQuestionIds();
        questionIds.add(questionId);
        favorites.setQuestionIds(questionIds);
        favoritesMapper.updateById(favorites);
        return "success";
    }
     /**
      * 删除问题ID从收藏
      * @param questionId
      * @param favoriteId
      * @return
      */
     public String deleteQuestionId(Long questionId,Long favoriteId) {
        Favorites favorites = favoritesMapper.selectById(favoriteId);
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        if(!favorites.getQuestionIds().contains(questionId)) {
            throw new IllegalArgumentException("该收藏不存在该问题");
        }
        List<Long>questionIds=favorites.getQuestionIds();
        questionIds.remove(questionId);
        favorites.setQuestionIds(questionIds);
        favoritesMapper.updateById(favorites);
        return "success";
     }
    /**
     * 删除收藏
     * @param favoriteId
     * @return
     */
    public String deleteFavorites(Long favoriteId) {
        Favorites favorites = favoritesMapper.selectById(favoriteId);
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        favoritesMapper.deleteById(favoriteId);
        return "success";
    }
    /**
     * 更新收藏
     * 更新收藏的字段可以为空，为空则不更新
     * @param receiveDto
     * @return
     */
    public String updateFavorites(ReceiveDto receiveDto) {
        int count=0;
        StringBuilder sb=new StringBuilder();
        Favorites favorites=favoritesMapper.selectById(receiveDto.getFavoritesId());
        if(favorites == null) {
            throw new IllegalArgumentException("未找到该收藏");
        }
        if(!favorites.getUserId().equals(UserContext.getUserId())) {
            throw new IllegalArgumentException("该收藏不是您的收藏");
        }
        if(receiveDto.getFavoritesName() != null) {
            count++;
            favorites.setFavoritesName(receiveDto.getFavoritesName());
        }
        if(receiveDto.getFavoritesContent() != null) {
            count++;
            favorites.setFavoritesContent(receiveDto.getFavoritesContent());
        }
        if(receiveDto.getFavoritesType() != null) {
            count++;
            favorites.setFavoritesType(receiveDto.getFavoritesType());
        }
        if(receiveDto.getQuestionIds() != null) {
            count++;
            favorites.setQuestionIds(receiveDto.getQuestionIds());
        }   
        if(count>0) {
            sb.append("更新成功");
            favoritesMapper.updateById(favorites);
        } else {
            sb.append("未更新任何字段");
        }
        return sb.toString();
    }

}
