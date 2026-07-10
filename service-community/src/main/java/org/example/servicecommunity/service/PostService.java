package org.example.servicecommunity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.example.servicecommon.RedisDto.RedisContext;
import org.example.servicecommon.until.UserContext;
import org.example.servicecommunity.Dto.PostDto;
import org.example.servicecommunity.entry.LikeRecord;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.entry.Tags;
import org.example.servicecommunity.mapper.LikeRecordMapper;
import org.example.servicecommunity.mapper.PostMapper;
import org.example.servicecommunity.mapper.TagsMapper;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomePostVo;
import org.example.servicecommunity.vo.PostVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class PostService {
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private TagsMapper tagsMapper;

    @Autowired
    private LikeRecordMapper likeRecordMapper;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;


    public String getRequestId(){
        return UUID.randomUUID().toString();
    }
    @Transactional
    public Post createPost(PostDto postDto,String uuid) {
        if(postDto.getPostTitle() == null || postDto.getPostContent() == null){
            throw new IllegalArgumentException("标题和内容不能为空");
        }
        if(postDto.getPostTitle().length() > 200){
            throw  new IllegalArgumentException("标题内容过长");
        }
        if(Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(RedisContext.REQUEST_ID_KEY+uuid,"pending",3, TimeUnit.MINUTES))){
            log.info("重复尝试");
            return null;
        }
        //测试结束初始状态需要改为0
        Post post = Post.builder().postTitle(postDto.getPostTitle())
                .postContent(postDto.getPostContent()).userId(UserContext.getUserId())
                .userName(UserContext.getUserName()).likeCount(0L)
                .commentCount(0L).status(1).createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build();
        int r1=postMapper.insert(post);
        if(r1==0){
            throw new IllegalArgumentException("发布失败");
        }
        List<Tags>tags=new ArrayList<>();
        for(String tag : postDto.getTags()){
            if(tag.length()<20){
                tags.add(Tags.builder().tagName(tag).postId(post.getPostId()).build());
            }
        }
        if(!tags.isEmpty()){
        int r2=tagsMapper.batchInsert(tags);
        if(r2==0){
            log.info("标签添加失败");
            throw new IllegalArgumentException("发布失败");
        }}
        redisTemplate.opsForValue().set(RedisContext.REQUEST_ID_KEY+uuid,"success",3, TimeUnit.MINUTES);
        return post;
    }



    public CursorPageResult<HomePostVo> cursorQuestions(Long lastId, Integer pageSize) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();

        // 游标分页：如果传入了 lastId，则查询大于 lastId 的记录（升序）
        if (lastId != null) {
            wrapper.gt(Post::getPostId, lastId);  // 改为 gt（大于），从前往后
        }


        // 按 questionId 升序排列（从小到大）
        wrapper.orderByAsc(Post::getPostId);  // 改为升序
        wrapper.last("LIMIT " + (pageSize + 1));

        List<Post> list = postMapper.selectList(wrapper);
        List<HomePostVo> returnPostVoList = new ArrayList<>();
        for(Post post:list){
            HomePostVo postVo=new HomePostVo(post);
            returnPostVoList.add(postVo);
        }

        List<HomePostVo> records;

        Long nextCursor = null;
        Boolean hasNext = false;

        if (list != null && !list.isEmpty()) {
            if (list.size() > pageSize) {
                // 取前 pageSize 条作为当前页
                records = returnPostVoList.subList(0, pageSize);
                // 获取最后一条记录的 ID 作为下一页的游标
                HomePostVo lastRecord = records.getLast();
                nextCursor = lastRecord.getPostId();
                hasNext = true;
            } else {
                records =  returnPostVoList;
                hasNext = false;
            }
        } else {
            records = new ArrayList<>();
        }
        List<Long> postIds = new ArrayList<>();
        for(HomePostVo postVo:returnPostVoList){
            postIds.add(postVo.getPostId());
        }
        Map<Long,List<String>> tagsMap = new HashMap<>();
        List<Tags> postTags = postIds.isEmpty() ? Collections.emptyList() : tagsMapper.batchSelectForPostId(postIds);
        for (Tags tag : postTags) {
            tagsMap.computeIfAbsent(tag.getPostId(), key -> new ArrayList<>()).add(tag.getTagName());
        }
        for(HomePostVo postVo:returnPostVoList){
            postVo.setTags(tagsMap.getOrDefault(postVo.getPostId(),Collections.emptyList()));
        }

        return CursorPageResult.<HomePostVo>builder()
                .records(records)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    public PostVo getPostById(Long postId){
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Post::getPostId,postId);
        Post post = postMapper.selectOne(wrapper);
        List<Tags>tags=tagsMapper.selectList(new LambdaQueryWrapper<Tags>().eq(Tags::getPostId,postId));
        //每个标签推荐三个最热,键为标签，列表为id集合
        List<Tags> relatedTags = tags.isEmpty() ? Collections.emptyList() : tagsMapper.BatchSelectPostFromTagName(tags);
        Map<String, List<Long>> map = new HashMap<>();
        for (Tags tag : relatedTags) {
            map.computeIfAbsent(tag.getTagName(), key -> new ArrayList<>()).add(tag.getPostId());
        }
        Map<String,Map<Long,String>>relatedPost=new HashMap<>();
        for(Map.Entry<String, List<Long>> entry:map.entrySet()){
           List<Post> relatedPosts = entry.getValue().isEmpty() ? Collections.emptyList() : postMapper.BatchSelectTitlesForId(entry.getValue());
           Map<Long,String> idAndTitle = new HashMap<>();
           for (Post related : relatedPosts) {
               idAndTitle.put(related.getPostId(), related.getPostTitle());
           }
           relatedPost.put(entry.getKey(),idAndTitle);
        }
        PostVo postVo=new PostVo(post);
        postVo.setRelatedPost(relatedPost);
        postVo.setIsLike(likeRecordMapper.selectOne(new QueryWrapper<LikeRecord>()
                .eq("post_id", postVo.getPostId())
                .eq("user_id", UserContext.getUserId())
                .eq("type", "POST")) != null);


         return postVo;
    }



}
