package com.cpa.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtAccountData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 已注册账号库表Repository
 */
@Mapper
public interface TtAccountDataRepository extends BaseMapper<TtAccountData> {

    /**
     * 查询需要养号的账号
     */
    @Select("SELECT * FROM tt_account_data WHERE nurture_status = 0 AND status = 0")
    List<TtAccountData> findAccountsNeedNurture();

    /**
     * 查询养号完成的账号
     */
    @Select("SELECT * FROM tt_account_data WHERE nurture_status = 1 AND status = 0")
    List<TtAccountData> findNurturedAccounts();

    /**
     * 根据国家查询账号
     */
    @Select("SELECT * FROM tt_account_data WHERE country = #{country} AND status = 0")
    List<TtAccountData> findByCountry(@Param("country") String country);

    /**
     * 根据包名查询账号
     */
    @Select("SELECT * FROM tt_account_data WHERE pkg_name = #{pkgName} AND status = 0")
    List<TtAccountData> findByPkgName(@Param("pkgName") String pkgName);

    /**
     * 批量更新刷视频天数
     */
    @Update("UPDATE tt_account_data SET video_days = video_days + 1 WHERE id IN " +
            "(SELECT id FROM (SELECT id FROM tt_account_data WHERE nurture_status = 0 AND status = 0 LIMIT #{limit}) t)")
    int batchUpdateVideoDays(@Param("limit") int limit);

    /**
     * 批量更新养号状态为完成
     */
    @Update("UPDATE tt_account_data SET nurture_status = 1 WHERE video_days >= #{days} AND nurture_status = 0")
    int batchUpdateNurtureStatus(@Param("days") int days);

    /**
     * 统计各状态账号数量
     */
    @Select("SELECT status, COUNT(*) as count FROM tt_account_data GROUP BY status")
    List<Map<String, Object>> countByStatus();

    /**
     * 统计养号进度
     */
    @Select("SELECT nurture_status, COUNT(*) as count FROM tt_account_data GROUP BY nurture_status")
    List<Map<String, Object>> countByNurtureStatus();

    /**
     * 统计刷视频天数分布
     */
    @Select("SELECT " +
            "CASE " +
            "  WHEN video_days = 0 THEN '0天' " +
            "  WHEN video_days BETWEEN 1 AND 3 THEN '1-3天' " +
            "  WHEN video_days BETWEEN 4 AND 6 THEN '4-6天' " +
            "  WHEN video_days >= 7 THEN '7天以上' " +
            "END as days_range, " +
            "COUNT(*) as count " +
            "FROM tt_account_data " +
            "GROUP BY days_range")
    List<Map<String, Object>> countVideoDaysDistribution();
    
    /**
     * 获取自动养号设备列表
     */
    @Select("SELECT phone_id, phone_server_id as serverIp, pkg_name as pkgName, status, upload_status as uploadStatus " +
            "FROM tt_account_data " +
            "WHERE phone_server_id = #{phoneServerId} " +
            "AND status = #{status} " +
            "AND upload_status = #{uploadStatus}")
    List<com.cpa.entity.DeviceInfo> getDeviceList(@Param("phoneServerId") String phoneServerId,
                                                    @Param("status") Integer status,
                                                    @Param("uploadStatus") Integer uploadStatus);
    
    /**
     * 根据phone_id更新刷视频天数+1
     */
    @Update("UPDATE tt_account_data SET video_days = video_days + 1 WHERE phone_id = #{phoneId}")
    int updateVideoDaysByPhoneId(@Param("phoneId") String phoneId);
}
