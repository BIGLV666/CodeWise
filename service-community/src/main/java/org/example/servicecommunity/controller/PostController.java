package org.example.servicecommunity.controller;

import org.example.serviceapi.dto.Result;
import org.example.servicecommunity.Dto.PostDto;
import org.example.servicecommunity.entry.Post;
import org.example.servicecommunity.service.PostService;
import org.example.servicecommunity.service.HostPostService;
import org.example.servicecommunity.vo.CursorPageResult;
import org.example.servicecommunity.vo.HomePostVo;
import org.example.servicecommunity.vo.PostVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/community")
public class PostController {

    @Autowired
    private PostService postService;
    @Autowired
    private HostPostService hostPostService;

    /**
     * 获取发布帖子或评论时使用的一次性请求 ID，防止重复提交。
     */
    @GetMapping("/request-id")
    public Result<String> getRequestId() {
        return Result.success(postService.getRequestId());
    }

    /**
     * 发布帖子。requestId 必须先通过 /request-id 获取。
     */
    @PostMapping("/posts")
    public Result<Post> createPost(@RequestBody PostDto postDto,
                                   @RequestParam String requestId) {
        return Result.success(postService.createPost(postDto, requestId));
    }

    /**
     * 按帖子 ID 升序游标分页。首次不传 lastId，后续传上页返回的 nextCursor。
     */
    @GetMapping("/posts")
    public Result<CursorPageResult<HomePostVo>> listPosts(
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        validatePageSize(pageSize);
        return Result.success(postService.cursorQuestions(lastId, pageSize));
    }

    /**
     * 查询帖子正文、点赞状态以及按标签推荐的相关帖子。
     */
    @GetMapping("/posts/{postId}")
    public Result<PostVo> getPost(@PathVariable Long postId) {
        return Result.success(postService.getPostById(postId));
    }

    /**
     * 修改当前用户发布的帖子。postId 以路径参数为准，请求体只需提交标题、正文和标签。
     */
    @PutMapping("/posts/{postId}")
    public Result<PostVo> updatePost(@PathVariable Long postId, @RequestBody PostVo postVo) {
        return Result.success(postService.updatePost(postId, postVo));
    }

    /**
     * 获取当前热度最高的帖子，按热度从高到低返回，最多 10 条。
     */
    @GetMapping("/posts/hot")
    public Result<List<HomePostVo>> hotPosts() {
        return Result.success(hostPostService.getHostHomePost());
    }

    /**
     * 按帖子标题模糊搜索，结果按发布时间倒序返回。
     */
    @GetMapping("/posts/search")
    public Result<List<HomePostVo>> searchPosts(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "20") Integer limit) {
        validatePageSize(limit);
        return Result.success(postService.searchPostsByTitle(keyword, limit));
    }

    /**
     * 按完整标签名搜索帖子，结果按发布时间倒序返回。
     */
    @GetMapping("/posts/tag")
    public Result<List<HomePostVo>> postsByTag(
            @RequestParam String tag,
            @RequestParam(defaultValue = "20") Integer limit) {
        validatePageSize(limit);
        return Result.success(postService.searchPostsByTag(tag, limit));
    }

    @DeleteMapping("/posts/{postId}")
    public Result<String> deletePost(@PathVariable Long postId) {
        postService.deletePostById(postId);
        return Result.success("success");
    }

    private void validatePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize 必须在 1 到 100 之间");
        }
    }
}
