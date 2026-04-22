package com.cpa.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cpa.entity.TtRegisterDispatchLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TtRegisterDispatchLogRepository extends BaseMapper<TtRegisterDispatchLog> {

    @Select("<script>" +
            "SELECT * FROM tt_register_dispatch_log WHERE 1=1 " +
            "<if test='taskId != null and taskId != \"\"'> AND task_id LIKE CONCAT('%', #{taskId}, '%') </if>" +
            "<if test='serverIp != null and serverIp != \"\"'> AND server_ip = #{serverIp} </if>" +
            "<if test='phoneId != null and phoneId != \"\"'> AND phone_id LIKE CONCAT('%', #{phoneId}, '%') </if>" +
            "ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}" +
            "</script>")
    List<TtRegisterDispatchLog> listLogs(@Param("taskId") String taskId,
                                         @Param("serverIp") String serverIp,
                                         @Param("phoneId") String phoneId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM tt_register_dispatch_log WHERE 1=1 " +
            "<if test='taskId != null and taskId != \"\"'> AND task_id LIKE CONCAT('%', #{taskId}, '%') </if>" +
            "<if test='serverIp != null and serverIp != \"\"'> AND server_ip = #{serverIp} </if>" +
            "<if test='phoneId != null and phoneId != \"\"'> AND phone_id LIKE CONCAT('%', #{phoneId}, '%') </if>" +
            "</script>")
    long countLogs(@Param("taskId") String taskId,
                   @Param("serverIp") String serverIp,
                   @Param("phoneId") String phoneId);
}
