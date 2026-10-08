package com.gameplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gameplatform.entity.ApiToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * API 令牌 Mapper（ADR-0029）。
 *
 * <p>只用 {@code IS NULL}/{@code <}/{@code =}，不含方言函数，三库共用；
 * {@code LocalDateTime} 作参数在 SQLite/MySQL/PG 均已有先例（参见 {@link UserMapper#updateLoginInfo}）。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Mapper
public interface ApiTokenMapper extends BaseMapper<ApiToken> {

    /**
     * 认证查询：按哈希取记录并带出归属用户名。不过滤 expires_at（过期在 Java 侧判，ADR-0029 D2）。
     */
    @Select("SELECT t.*, u.username FROM api_token t " +
            "JOIN sys_user u ON u.id = t.user_id " +
            "WHERE t.token_hash = #{hash} AND t.is_deleted = 0")
    ApiToken selectActiveByHash(@Param("hash") String hash);

    /**
     * 节流写：是否要写由 SQL 自己判，返回 0 表示本次不写（ADR-0029 D6）。
     */
    @Update("UPDATE api_token SET last_used_at = #{now} " +
            "WHERE id = #{id} AND (last_used_at IS NULL OR last_used_at < #{threshold})")
    int touchLastUsed(@Param("id") Long id, @Param("now") LocalDateTime now,
                      @Param("threshold") LocalDateTime threshold);

    /**
     * 当前用户未吊销令牌数（配额检查）。
     */
    @Select("SELECT COUNT(*) FROM api_token WHERE user_id = #{userId} AND revoked = 0 AND is_deleted = 0")
    int countActiveByUser(@Param("userId") Long userId);

    /**
     * 当前用户下同名未吊销令牌数（重名检查）。
     */
    @Select("SELECT COUNT(*) FROM api_token WHERE user_id = #{userId} AND name = #{name} " +
            "AND revoked = 0 AND is_deleted = 0")
    int countActiveByName(@Param("userId") Long userId, @Param("name") String name);

    /**
     * 吊销（幂等：只动未吊销的行，二次调用受影响行 0 且不覆盖 revoked_at）。
     */
    @Update("UPDATE api_token SET revoked = 1, revoked_at = #{now}, update_time = #{now} " +
            "WHERE id = #{id} AND revoked = 0 AND is_deleted = 0")
    int revokeById(@Param("id") Long id, @Param("now") LocalDateTime now);

}
