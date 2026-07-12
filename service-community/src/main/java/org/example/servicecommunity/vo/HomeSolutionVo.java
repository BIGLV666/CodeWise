package org.example.servicecommunity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.serviceapi.dto.UserDto;
import org.example.servicecommunity.entry.Solution;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeSolutionVo {
    private Long solutionId;
    private Long questionId;
    private String solutionTitle;
    private UserDto userDto;
    private String solutionUserId;
    private Long likeCount;
    private Long lookCount;
    private Long commentCount;
    private List<String> tags;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public HomeSolutionVo(Solution solution) {
        this.solutionId = solution.getSolutionId();
        this.questionId = solution.getQuestionId();
        this.solutionTitle = solution.getSolutionTitle();

        this.solutionUserId = solution.getSolutionUserId().toString();
        this.likeCount = solution.getLikeCount();
        this.lookCount = solution.getLookCount();
        this.commentCount = solution.getCommentCount();
        this.createTime = solution.getCreateTime();
        this.updateTime = solution.getUpdateTime();
    }
}
