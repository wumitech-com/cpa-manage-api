package com.cpa.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtTaskTemplate;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TtTaskTemplateRepository extends BaseMapper<TtTaskTemplate> {

    default TtTaskTemplate findByTemplateCode(String templateCode) {
        if (templateCode == null || templateCode.isBlank()) return null;
        return selectOne(new LambdaQueryWrapper<TtTaskTemplate>()
                .eq(TtTaskTemplate::getTemplateCode, templateCode.trim()));
    }
}
