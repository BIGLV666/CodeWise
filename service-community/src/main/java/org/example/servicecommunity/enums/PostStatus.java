package org.example.servicecommunity.enums;

/**
 * 社区内容（帖子 / 评论 / 题解）的审核状态。
 *
 * <p>状态值与数据库中既有的 tinyint 保持一致，因此这里用 int 常量而非枚举，
 * 以免影响现有 MyBatis 映射与历史数据。</p>
 */
public final class PostStatus {

    /** 待审核：仅作者本人与管理员可见。 */
    public static final int PENDING = 0;

    /** 审核通过：公开可见。 */
    public static final int NORMAL = 1;

    /** 已下架 / 审核不通过：不公开，作者可见状态与原因。 */
    public static final int TACK_DOWN = 2;

    private PostStatus() {
    }

    /** 是否为公开可见状态。 */
    public static boolean isVisible(Integer status) {
        return Integer.valueOf(NORMAL).equals(status);
    }
}
