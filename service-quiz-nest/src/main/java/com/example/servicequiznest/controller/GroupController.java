package com.example.servicequiznest.controller;

import com.example.servicequiznest.dto.CreateGroupDto;
import com.example.servicequiznest.dto.GroupDto;
import com.example.servicequiznest.dto.JoinGroupDto;
import com.example.servicequiznest.dto.SendJoinTokenDto;
import com.example.servicequiznest.entity.Group;
import com.example.servicequiznest.service.GroupService;
import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.example.serviceapi.dto.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小组对外接口（网关路由 {@code /api/quiz/**}）。
 *
 * <p>身份一律来自网关注入后由 {@code UserAuthInterceptor} 写入的
 * {@code UserContext}，不接收客户端传来的 userId。</p>
 *
 * <p>限流档位：建组/发邀请/加入 5-10 次/分（写操作 + 邀请码防爆破），
 * 改组/退出 10-20 次/分，详情/创建凭证 20-100 次/分。邀请码只有 6 位，
 * 依赖这里的用户维度限流（登录用户按 userId 计数，换 IP 无效）来限制穷举。</p>
 */
@RestController
@RequestMapping("/api/quiz/groups")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /**
     * 获取创建小组用的一次性凭证（10 分钟内有效，重复提交同一 token 会被拒绝）。
     */
    @GetMapping("/token")
    @RateLimit(limit = 20, window = 60)
    public Result<String> getCreateToken() {
        // 显式走 (data, message) 重载：Result.success(String) 是 message 重载，
        // 单参数传 String 会把凭证放进 message、data 为 null。
        return Result.success(groupService.getToken(), "success");
    }

    /**
     * 创建小组，创建人自动成为 ROOT。
     */
    @PostMapping
    @RateLimit(limit = 5, window = 60)
    public Result<Group> createGroup(@RequestBody CreateGroupDto createGroupDto) {
        return Result.success(groupService.cerateGroup(createGroupDto));
    }

    /**
     * 修改小组（仅 ROOT/ADMIN）。请求体必须携带完整字段。
     */
    @PutMapping
    @RateLimit(limit = 20, window = 60)
    public Result<Group> updateGroup(@RequestBody GroupDto groupDto) {
        return Result.success(groupService.updateGroup(groupDto));
    }

    /**
     * 查询小组基础信息。
     */
    @GetMapping("/{groupId}")
    @RateLimit(limit = 100, window = 60)
    public Result<Group> getGroup(@PathVariable Long groupId) {
        return Result.success(groupService.getGroup(groupId));
    }

    /**
     * 退出小组；ROOT 可用 {@code lastRoot} 指定继承人，未指定则自动晋升。
     */
    @DeleteMapping("/{groupId}/members/me")
    @RateLimit(limit = 10, window = 60)
    public Result<Boolean> leaveGroup(@PathVariable Long groupId,
                                      @RequestParam(required = false) Long lastRoot) {
        return Result.success(groupService.leaveGroup(groupId, lastRoot));
    }

    /**
     * 加入小组。FREE/APPROVAL 无需请求体；INVITE 小组必须在请求体里带邀请码。
     */
    @PostMapping("/{groupId}/join")
    @RateLimit(limit = 10, window = 60)
    public Result<Boolean> joinGroup(@PathVariable Long groupId,
                                     @RequestBody(required = false) JoinGroupDto joinGroupDto) {
        groupService.joinGroup(groupId, joinGroupDto == null ? null : joinGroupDto.getToken());
        return Result.success(true);
    }

    /**
     * 向指定用户发送邀请码站内信（仅本组 ROOT/ADMIN，且仅 INVITE 小组）。
     */
    @PostMapping("/{groupId}/invitations")
    @RateLimit(limit = 5, window = 60)
    public Result<Void> sendJoinToken(@PathVariable Long groupId,
                                      @RequestBody SendJoinTokenDto sendJoinTokenDto) {
        groupService.sendJoinTokenById(
                groupId, sendJoinTokenDto.getToUserId(), sendJoinTokenDto.getContent());
        return Result.success("邀请码已发送");
    }
}
