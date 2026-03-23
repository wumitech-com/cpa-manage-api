package com.cpa.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtRetentionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 留存任务执行记录 Repository
 */
@Mapper
public interface TtRetentionRecordRepository extends BaseMapper<TtRetentionRecord> {
    /**
     * 查询指定时间范围内的留存记录列表（按时间倒序）
     */
    @Select("SELECT * FROM tt_retention_record " +
            "WHERE created_at >= #{start} AND created_at <= #{end} " +
            "ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<TtRetentionRecord> listByCreatedBetween(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end,
                                                 @Param("offset") int offset,
                                                 @Param("limit") int limit);

    /**
     * 统计指定时间范围内留存脚本/备份成功情况
     */
    @Select("SELECT " +
            "COUNT(*) AS total_cnt, " +
            "SUM(CASE WHEN script_success = 1 THEN 1 ELSE 0 END) AS script_ok, " +
            "SUM(CASE WHEN script_success = 0 THEN 1 ELSE 0 END) AS script_fail, " +
            "SUM(CASE WHEN backup_success = 1 THEN 1 ELSE 0 END) AS backup_ok, " +
            "SUM(CASE WHEN backup_success = 0 THEN 1 ELSE 0 END) AS backup_fail " +
            "FROM tt_retention_record " +
            "WHERE created_at >= #{start} AND created_at <= #{end}")
    Map<String, Object> statByCreatedBetween(@Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);

    /**
     * 统计指定时间范围内：留存完成并2FA成功的账号数（去重账号）
     */
    @Select("SELECT COUNT(DISTINCT a.id) " +
            "FROM tt_retention_record r " +
            "JOIN tt_account_register a ON a.id = r.account_register_id " +
            "WHERE r.created_at >= #{start} AND r.created_at <= #{end} " +
            "AND r.account_register_id IS NOT NULL " +
            "AND a.need_retention = 1 " +
            "AND a.is_2fa_setup_success = 1")
    long countRetention2faSuccessByCreatedBetween(@Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    /**
     * 统计指定时间范围内：留存账号登出数（need_retention=2，去重账号）
     */
    @Select("SELECT COUNT(DISTINCT a.id) " +
            "FROM tt_retention_record r " +
            "JOIN tt_account_register a ON a.id = r.account_register_id " +
            "WHERE r.created_at >= #{start} AND r.created_at <= #{end} " +
            "AND r.account_register_id IS NOT NULL " +
            "AND a.need_retention = 2")
    long countRetentionLogoutByCreatedBetween(@Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /**
     * 统计某一天（时间范围内）留存脚本+备份都成功的账号 cohort 的总数和封号数
     */
    @Select("SELECT " +
            "COUNT(*) AS total_cnt, " +
            "SUM(CASE WHEN a.block_time IS NOT NULL THEN 1 ELSE 0 END) AS blocked_cnt, " +
            "SUM(CASE WHEN a.need_retention = 2 THEN 1 ELSE 0 END) AS logout_cnt " +
            "FROM tt_account_register a " +
            "WHERE a.id IN ( " +
            "  SELECT DISTINCT r.account_register_id " +
            "  FROM tt_retention_record r " +
            "  WHERE r.created_at >= #{start} AND r.created_at <= #{end} " +
            "    AND r.script_success = 1 " +
            "    AND r.backup_success = 1 " +
            "    AND r.account_register_id IS NOT NULL " +
            ")")
    Map<String, Object> statCohortBlockWithBackup(@Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    /**
     * 统计全部留存脚本+备份都成功的账号 cohort 的总数和封号数
     */
    @Select("SELECT " +
            "COUNT(*) AS total_cnt, " +
            "SUM(CASE WHEN a.block_time IS NOT NULL THEN 1 ELSE 0 END) AS blocked_cnt, " +
            "SUM(CASE WHEN a.need_retention = 2 THEN 1 ELSE 0 END) AS logout_cnt " +
            "FROM tt_account_register a " +
            "WHERE a.id IN ( " +
            "  SELECT DISTINCT r.account_register_id " +
            "  FROM tt_retention_record r " +
            "  WHERE r.script_success = 1 " +
            "    AND r.backup_success = 1 " +
            "    AND r.account_register_id IS NOT NULL " +
            ")")
    Map<String, Object> statAllCohortBlockWithBackup();
}
