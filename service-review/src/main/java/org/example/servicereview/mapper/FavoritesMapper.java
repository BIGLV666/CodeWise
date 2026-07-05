package org.example.servicereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.servicereview.entry.Favorites;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FavoritesMapper extends BaseMapper<Favorites> {
}