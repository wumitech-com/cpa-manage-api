package com.cpa.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtTaskExecutionLog;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TtTaskExecutionLogRepository extends BaseMapper<TtTaskExecutionLog> {

    default List<TtTaskExecutionLog> listByInstanceId(String instanceId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 1000);
        return selectList(new LambdaQueryWrapper<TtTaskExecutionLog>()
                .eq(TtTaskExecutionLog::getInstanceId, instanceId)
                .orderByDesc(TtTaskExecutionLog::getCreatedAt)
                .last("LIMIT " + safeLimit));
    }
}
