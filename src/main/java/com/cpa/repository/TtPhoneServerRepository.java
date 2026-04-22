package com.cpa.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtPhoneServer;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface TtPhoneServerRepository extends BaseMapper<TtPhoneServer> {

    default Optional<TtPhoneServer> findByServerIp(String serverIp) {
        if (serverIp == null || serverIp.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<TtPhoneServer> qw = new LambdaQueryWrapper<TtPhoneServer>()
                .eq(TtPhoneServer::getServerIp, serverIp.trim());
        return Optional.ofNullable(selectOne(qw));
    }
}
