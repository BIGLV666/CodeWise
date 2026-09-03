package org.example.servicereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.servicereview.entry.Favorites;

import java.util.List;

@Mapper
public interface FavoritesMapper extends BaseMapper<Favorites> {

    /**
     * 按主键升序对两条收藏夹记录加行锁（FOR UPDATE），供跨夹移动在事务内使用。
     *
     * <p>固定按 favorites_id 升序加锁，保证两个并发移动操作以相同顺序获取锁，
     * 避免交叉加锁死锁。只返回 ID 列表（不做 JSON 列映射），完整实体由调用方
     * 在同一事务内以 selectById 读取（行已被当前事务锁定）。</p>
     *
     * @param fromId 源收藏夹 ID
     * @param toId   目标收藏夹 ID
     * @return 实际存在并已锁定的收藏夹 ID
     */
    @Select("SELECT favorites_id FROM favorites WHERE favorites_id IN (#{fromId}, #{toId}) ORDER BY favorites_id FOR UPDATE")
    List<Long> lockByIdsForUpdate(@Param("fromId") Long fromId, @Param("toId") Long toId);
}
