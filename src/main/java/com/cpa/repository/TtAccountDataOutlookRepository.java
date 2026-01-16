package com.cpa.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtAccountDataOutlook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 待注册设备池表Repository
 */
@Mapper
public interface TtAccountDataOutlookRepository extends BaseMapper<TtAccountDataOutlook> {

    /**
     * 查询需要注册的设备（邮箱或TT账号为空）
     */
    @Select("SELECT * FROM tt_account_data_outlook WHERE (email_account IS NULL OR tt_user_name IS NULL) AND status = 0")
    List<TtAccountDataOutlook> findDevicesNeedRegister();

    /**
     * 根据国家查询设备
     */
    @Select("SELECT * FROM tt_account_data_outlook WHERE country = #{country} AND status = 0")
    List<TtAccountDataOutlook> findByCountry(@Param("country") String country);

    /**
     * 根据包名查询设备
     */
    @Select("SELECT * FROM tt_account_data_outlook WHERE pkg_name = #{pkgName} AND status = 0")
    List<TtAccountDataOutlook> findByPkgName(@Param("pkgName") String pkgName);

    /**
     * 统计各状态设备数量
     */
    @Select("SELECT status, COUNT(*) as count FROM tt_account_data_outlook GROUP BY status")
    List<Map<String, Object>> countByStatus();
}
