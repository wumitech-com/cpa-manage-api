package com.cpa.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtEmailPool;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;
import java.util.List;

@Mapper
public interface TtEmailPoolRepository extends BaseMapper<TtEmailPool> {

    default Optional<TtEmailPool> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<TtEmailPool> query = new LambdaQueryWrapper<TtEmailPool>()
                .eq(TtEmailPool::getEmail, email.trim());
        return Optional.ofNullable(selectOne(query));
    }

    default List<TtEmailPool> listByEmails(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<TtEmailPool> query = new LambdaQueryWrapper<TtEmailPool>()
                .in(TtEmailPool::getEmail, emails);
        return selectList(query);
    }

    @Insert({
            "<script>",
            "INSERT INTO tt_email_pool (email, password, client_id, refresh_token, channel, usage_status, created_at, updated_at) VALUES",
            "<foreach collection='rows' item='item' separator=','>",
            "(#{item.email}, #{item.password}, #{item.clientId}, #{item.refreshToken}, #{item.channel}, #{item.usageStatus}, #{item.createdAt}, #{item.updatedAt})",
            "</foreach>",
            "ON DUPLICATE KEY UPDATE",
            "password = VALUES(password),",
            "client_id = VALUES(client_id),",
            "refresh_token = VALUES(refresh_token),",
            "channel = COALESCE(VALUES(channel), channel),",
            "updated_at = VALUES(updated_at)",
            "</script>"
    })
    int batchUpsert(@Param("rows") List<TtEmailPool> rows);

    @Select("SELECT COUNT(*) FROM tt_email_pool WHERE usage_status = 'UNUSED'")
    long countUnusedEmails();

    @Select("SELECT COUNT(*) FROM tt_email_pool WHERE usage_status = 'USED' AND updated_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)")
    long countConsumedInLast24Hours();
}
