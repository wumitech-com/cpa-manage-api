package com.cpa.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtTaskBatch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TtTaskBatchRepository extends BaseMapper<TtTaskBatch> {

    default TtTaskBatch findByBatchId(String batchId) {
        if (batchId == null || batchId.isBlank()) return null;
        return selectOne(new LambdaQueryWrapper<TtTaskBatch>()
                .eq(TtTaskBatch::getBatchId, batchId.trim()));
    }
}
