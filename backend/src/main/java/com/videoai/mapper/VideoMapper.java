package com.videoai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoai.entity.Video;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VideoMapper extends BaseMapper<Video> {
}
