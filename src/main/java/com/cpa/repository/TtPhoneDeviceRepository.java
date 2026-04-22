package com.cpa.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtPhoneDevice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface TtPhoneDeviceRepository extends BaseMapper<TtPhoneDevice> {

    default Optional<TtPhoneDevice> findByPhoneId(String phoneId) {
        if (phoneId == null || phoneId.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<TtPhoneDevice> qw = new LambdaQueryWrapper<TtPhoneDevice>()
                .eq(TtPhoneDevice::getPhoneId, phoneId.trim());
        return Optional.ofNullable(selectOne(qw));
    }

    @Select("SELECT phone_id FROM tt_phone_device WHERE server_ip = #{serverIp} AND phone_id LIKE CONCAT(#{phoneIdPrefix}, '%') ORDER BY phone_id")
    java.util.List<String> listPhoneIdsByServerAndPrefix(@Param("serverIp") String serverIp, @Param("phoneIdPrefix") String phoneIdPrefix);
}
