package com.gameplatform.service;

import com.gameplatform.dto.ApiTokenCreateDTO;
import com.gameplatform.enums.TokenScope;
import com.gameplatform.vo.ApiTokenCreatedVO;
import com.gameplatform.vo.ApiTokenVO;
import lombok.Data;

import java.util.List;

/**
 * API 令牌服务（ADR-0029 长期可吊销凭证）。
 *
 * <p>本设计的唯一深模块：小接口（5 个方法）、大行为（生成、摘要存储、有效性裁定、
 * 作用域裁定、节流记账、吊销、列表投影）。过滤器与管理控制器都只是它的适配器。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface ApiTokenService {

    /** 明文前缀常量，过滤器与生成器共用（"gpm_"）。 */
    String PLAINTEXT_PREFIX = "gpm_";

    /** 签发一枚令牌；返回体含明文（仅此一次可见）。 */
    ApiTokenCreatedVO create(ApiTokenCreateDTO dto);

    /** 当前归属用户名下的全部令牌（含已吊销），投影不含明文与哈希。 */
    List<ApiTokenVO> list();

    /** 吊销（幂等：已吊销再次调用不报错、不覆盖 revoked_at）。 */
    ApiTokenVO revoke(Long id);

    /** 凭证 + 作用域一次性裁定；httpMethod 传 request.getMethod() 的大写串。 */
    Decision authenticate(String plainToken, String httpMethod);

    /** best-effort 记录使用时间，内部按配置节流；失败只记日志，绝不抛出。 */
    void touch(Long tokenId);

    /**
     * 裁定结果。INVALID 刻意合并"不存在/已吊销/已过期"，具体原因只在 reason 里供日志使用。
     *
     * <p>接缝纪律：{@link Outcome#ALLOW} 一定带 tokenId/tokenName/username/scope；
     * 非 ALLOW 一律只带 reason。</p>
     */
    @Data
    class Decision {

        public enum Outcome {
            ALLOW, INVALID, SCOPE_DENIED
        }

        private Outcome outcome;
        /** 仅日志用：not_found | revoked | expired | scope_read */
        private String reason;
        private Long tokenId;
        private String tokenName;
        /** 仅 ALLOW：归属用户，交给过滤器加载 UserDetails */
        private String username;
        private TokenScope scope;

        public static Decision invalid(String reason) {
            Decision d = new Decision();
            d.outcome = Outcome.INVALID;
            d.reason = reason;
            return d;
        }

        public static Decision scopeDenied(String reason) {
            Decision d = new Decision();
            d.outcome = Outcome.SCOPE_DENIED;
            d.reason = reason;
            return d;
        }

        public static Decision allow(Long tokenId, String tokenName, String username, TokenScope scope) {
            Decision d = new Decision();
            d.outcome = Outcome.ALLOW;
            d.tokenId = tokenId;
            d.tokenName = tokenName;
            d.username = username;
            d.scope = scope;
            return d;
        }
    }

}
