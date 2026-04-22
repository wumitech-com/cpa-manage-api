package com.cpa.repository;

import com.cpa.entity.TtAccountRegister;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * TT账号注册记录Repository
 */
@Mapper
public interface TtAccountRegisterRepository extends BaseMapper<TtAccountRegister> {

    /**
     * 今日注册数：当天入表总数（created_at 在区间内）
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end}")
    long countTodayRegister(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 区间内新增注册：android_version = '13'（对应业务 SDK API 33，与 proportion 里第 4 段一致）
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} AND android_version = '13'")
    long countCreatedInRangeForSdkApi33(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 区间内新增注册：android_version = '14'（对应业务 SDK API 34，与 proportion 里第 5 段一致）
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} AND android_version = '14'")
    long countCreatedInRangeForSdkApi34(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 今日2FA设置成功数：is_2fa_setup_success = 1
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} AND is_2fa_setup_success = 1")
    long countToday2faSuccess(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 今日注册成功数：register_success = 1
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} AND register_success = 1")
    long countTodayRegisterSuccess(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 今日注册成功后续留存做2FA成功数：need_retention = 1
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} AND need_retention = 1")
    long countTodayNeedRetention(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 指定日期 2FA 设置成功的账号总数（is_2fa_setup_success=1, created_at 在该日）
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} AND register_success = 1")
    long count2faSuccessByDate(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 指定日期：留存完成且2FA成功账号数（is_2fa_setup_success=1 AND need_retention=1）
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} " +
            "AND is_2fa_setup_success = 1 AND need_retention = 1")
    long countRetention2faSuccessByDate(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 指定日期：留存账号登出数（need_retention=2）
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} " +
            "AND need_retention = 2")
    long countRetentionLogoutByDate(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 指定日期 2FA 设置成功且已封号数（block_time IS NOT NULL）
     */
    @Select("SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} AND register_success = 1 AND block_time IS NOT NULL")
    long countBlockedByDate(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * cohort 日 2FA 成功数；{@code country} 为 null 时不按国家过滤（与 ALL 一致）
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at &gt;= #{start} AND created_at &lt;= #{end} AND register_success = 1 " +
            "<if test='country != null'>AND UPPER(TRIM(country)) = UPPER(TRIM(#{country})) </if>" +
            "</script>")
    long count2faSuccessByDateRangeAndCountry(@Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end,
                                              @Param("country") String country);

    /**
     * cohort 日内 2FA 成功且截至 {@code blockTimeLe}（含）已封号
     */
    @Select("<script>" +
            "SELECT COUNT(*) FROM tt_account_register " +
            "WHERE created_at &gt;= #{cohortStart} AND created_at &lt;= #{cohortEnd} " +
            "AND register_success = 1 AND block_time IS NOT NULL AND block_time &lt;= #{blockTimeLe} " +
            "<if test='country != null'>AND UPPER(TRIM(country)) = UPPER(TRIM(#{country})) </if>" +
            "</script>")
    long count2faBlockedByDateRangeAndCountryAndBlockTimeLe(@Param("cohortStart") LocalDateTime cohortStart,
                                                            @Param("cohortEnd") LocalDateTime cohortEnd,
                                                            @Param("blockTimeLe") LocalDateTime blockTimeLe,
                                                            @Param("country") String country);

    /**
     * cohort 日内 2FA 成功账号列表；{@code country} 为 null 时不按国家过滤
     */
    @Select("<script>" +
            "SELECT * FROM tt_account_register " +
            "WHERE created_at &gt;= #{start} AND created_at &lt;= #{end} AND register_success = 1 " +
            "<if test='country != null'>AND UPPER(TRIM(country)) = UPPER(TRIM(#{country})) </if>" +
            "</script>")
    List<TtAccountRegister> list2faSuccessByDateRangeAndCountry(@Param("start") LocalDateTime start,
                                                                  @Param("end") LocalDateTime end,
                                                                  @Param("country") String country);

    /**
     * 统计最近 N 天每日数据：注册数、2FA成功数、留存数
     */
    @Select("SELECT DATE(created_at) AS stat_date, " +
            "SUM(CASE WHEN username IS NOT NULL THEN 1 ELSE 0 END) AS register_cnt, " +
            "SUM(CASE WHEN is_2fa_setup_success = 1 THEN 1 ELSE 0 END) AS twofa_cnt, " +
            "SUM(CASE WHEN need_retention = 1 THEN 1 ELSE 0 END) AS retention_cnt " +
            "FROM tt_account_register WHERE created_at >= #{startDate} GROUP BY DATE(created_at) ORDER BY stat_date")
    List<Map<String, Object>> countDailyStats(@Param("startDate") LocalDateTime startDate);

    /**
     * 查询指定日期内 2FA 设置成功的账号列表
     */
    @Select("SELECT * FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} AND is_2fa_setup_success = 1")
    List<TtAccountRegister> list2faSuccessByDate(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    /**
     * 查询指定日期内 trafficData 非空的流量字段（用于全量流量统计）
     */
    @Select("SELECT trafficData FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} " +
            "AND trafficData IS NOT NULL AND TRIM(trafficData) <> ''")
    List<String> listTrafficDataByDate(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    /**
     * 指定日期内按服务器+小时统计 2FA 成功数
     */
    @Select("SELECT phone_server_ip AS server_ip, HOUR(created_at) AS hour_of_day, COUNT(*) AS cnt " +
            "FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} " +
            "AND is_2fa_setup_success = 1 " +
            "AND phone_server_ip IS NOT NULL AND phone_server_ip <> '' " +
            "GROUP BY phone_server_ip, HOUR(created_at) " +
            "ORDER BY phone_server_ip ASC, hour_of_day ASC")
    List<Map<String, Object>> count2faByServerAndHour(@Param("start") LocalDateTime start,
                                                       @Param("end") LocalDateTime end);

    /**
     * 指定日期内按服务器+小时统计注册成功数
     */
    @Select("SELECT phone_server_ip AS server_ip, HOUR(created_at) AS hour_of_day, COUNT(*) AS cnt " +
            "FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} " +
            "AND register_success = 1 " +
            "AND phone_server_ip IS NOT NULL AND phone_server_ip <> '' " +
            "GROUP BY phone_server_ip, HOUR(created_at) " +
            "ORDER BY phone_server_ip ASC, hour_of_day ASC")
    List<Map<String, Object>> countRegisterSuccessByServerAndHour(@Param("start") LocalDateTime start,
                                                                   @Param("end") LocalDateTime end);

    /**
     * 指定日期内按服务器+小时统计“注册尝试总数”（即 created_at 在范围内的记录总数）
     * 用于计算注册成功率：register_success / created_total
     */
    @Select("SELECT phone_server_ip AS server_ip, HOUR(created_at) AS hour_of_day, COUNT(*) AS cnt " +
            "FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} " +
            "AND phone_server_ip IS NOT NULL AND phone_server_ip <> '' " +
            "GROUP BY phone_server_ip, HOUR(created_at) " +
            "ORDER BY phone_server_ip ASC, hour_of_day ASC")
    List<Map<String, Object>> countCreatedByServerAndHour(@Param("start") LocalDateTime start,
                                                           @Param("end") LocalDateTime end);

    /**
     * 查询指定日期内 2FA 设置成功且已封号的账号列表（block_time IS NOT NULL）
     */
    @Select("SELECT * FROM tt_account_register " +
            "WHERE created_at >= #{start} AND created_at <= #{end} " +
            "AND is_2fa_setup_success = 1 AND block_time IS NOT NULL")
    List<TtAccountRegister> listBlocked2faByDate(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    /**
     * 留存任务：查询指定时间前注册成功、未封禁、未卖出的账号，随机排序取 limit 条
     * 条件：created_at &lt; cutoff, is_2fa_setup_success=1, block_time IS NULL, is_sell_out IS NULL, country=?
     */
    @Select("SELECT * FROM tt_account_register a " +
            "WHERE a.created_at < #{cutoff} " +
            "AND a.is_2fa_setup_success = 2 " +
            "AND a.block_time IS NULL " +
            "AND a.is_sell_out IS NULL " +
            "AND a.backup_success = 1 " +
            "AND a.country = #{country} " +
            "AND a.need_retention = 1 " +
            "AND NOT EXISTS (SELECT 1 FROM tt_retention_record r " +
            "               WHERE r.account_register_id = a.id " +
            "                 AND r.created_at > #{retentionCutoff}) " +
            "ORDER BY RAND() LIMIT #{limit}")
    List<TtAccountRegister> listForRetention(@Param("cutoff") LocalDateTime cutoff,
                                             @Param("country") String country,
                                             @Param("limit") int limit,
                                             @Param("retentionCutoff") LocalDateTime retentionCutoff);

    /**
     * 更新 need_retention 字段（用于留存脚本返回特定状态时标记账号）
     */
    @Update("UPDATE tt_account_register SET need_retention = #{needRetention} WHERE id = #{id}")
    int updateNeedRetention(@Param("id") Long id, @Param("needRetention") int needRetention);

    /**
     * 按 username 查找账号（导入时 upsert 匹配依据）
     */
    @Select("SELECT * FROM tt_account_register WHERE username = #{username} LIMIT 1")
    TtAccountRegister findByUsername(@Param("username") String username);

    /**
     * 按 gaid 查最新记录（用于设备恢复查看）
     */
    @Select("SELECT * FROM tt_account_register WHERE gaid = #{gaid} ORDER BY created_at DESC LIMIT 1")
    TtAccountRegister findLatestByGaid(@Param("gaid") String gaid);

    /**
     * 按 phone_id 查最新记录（用于获取目标云手机 server_ip）
     */
    @Select("SELECT * FROM tt_account_register WHERE phone_id = #{phoneId} ORDER BY created_at DESC LIMIT 1")
    TtAccountRegister findLatestByPhoneId(@Param("phoneId") String phoneId);
}

