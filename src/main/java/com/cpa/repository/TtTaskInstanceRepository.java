package com.cpa.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtTaskInstance;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TtTaskInstanceRepository extends BaseMapper<TtTaskInstance> {

    default TtTaskInstance findByInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) return null;
        return selectOne(new LambdaQueryWrapper<TtTaskInstance>()
                .eq(TtTaskInstance::getInstanceId, instanceId.trim()));
    }

    default List<TtTaskInstance> listByBatchId(String batchId) {
        return selectList(new LambdaQueryWrapper<TtTaskInstance>()
                .eq(TtTaskInstance::getBatchId, batchId)
                .orderByDesc(TtTaskInstance::getCreatedAt));
    }
}
