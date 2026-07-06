package org.example.servicereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.servicereview.entry.Review;

import java.util.List;

@Mapper
public interface ReviewMapper extends BaseMapper<Review> {
    List<Review> getReviews(@Param("total") Integer total,@Param("userId")Long userId);

    /**
     * 根据 SM-2 计算结果更新复习记录。
     */
    int updateReviewBySm2(Review review);
}
