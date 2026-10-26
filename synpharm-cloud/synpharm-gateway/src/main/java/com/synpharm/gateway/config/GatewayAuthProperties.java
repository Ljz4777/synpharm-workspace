package com.synpharm.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权配置。
 *
 * <p>演进过程：
 * <ul>
 *   <li><b>阶段 0（历史）</b>：{@code enforce=false} + {@code forwardAuthorization=true} ——
 *       只做「剥离伪造身份头 + 有效 Token 注入身份头」，未通过鉴权的请求原样转发给下游，
 *       因为那时单体还在自行验签 Token，改了就是行为变更。</li>
 *   <li><b>阶段 1 起（当前）</b>：auth 已独立，单体与各服务改为消费内部身份头，
 *       于是 {@code enforce=true}（网关成为唯一鉴权点）+
 *       {@code forwardAuthorization=false}（凭据不再透传下游）。</li>
 * </ul>
 *
 * <p><b>字段默认值一律取「安全侧」</b>：即使某个 profile 漏配了这两项，
 * 也应该是「拦截 + 不外泄凭据」，而不是相反。真正的开关由
 * {@code application.yml} 的 {@code GATEWAY_AUTH_ENFORCE} /
 * {@code GATEWAY_FORWARD_AUTHORIZATION} 环境变量覆盖。
 *
 * <p><b>不要把这些开关当作回退手段</b>：阶段 1 之后下游已经不再自行验签 Token，
 * 因此 {@code enforce=false} 并不能恢复阶段 0 的「放行 + 下游自己验」行为 ——
 * 它只会让受保护路径拿不到可信身份头，在服务侧表现为 401（即故障，而非降级）。
 * 真要回退，必须连同下游服务一起回到「自验签」状态。
 *
 * @author SynPharm Team
 */
@Data
@ConfigurationProperties(prefix = "synpharm.gateway.auth")
public class GatewayAuthProperties {

    /**
     * 是否强制拦截未通过鉴权的请求。
     *
     * <p>默认 {@code true}（fail-closed）：漏配配置时宁可多拦一批请求，
     * 也不能让未鉴权流量进到下游。
     */
    private boolean enforce = true;

    /**
     * 免鉴权路径（Ant 风格）。这些路径即使带了 Token 也不注入身份头。
     *
     * <p>两条纪律：
     * <ol>
     *   <li>只列**真正需要**的路径。多列的每一条都是一次静默放开。</li>
     *   <li>列表必须与下游各服务的公开接口清单保持同步 —— 网关放开了、下游没放开，
     *       或者反过来，都会让「谁能访问」出现两套答案。</li>
     * </ol>
     *
     * <p><b>本清单只对「已被路由匹配」的路径生效</b>。网关当前只声明了三条路由
     * （{@code /api/auth/** + /api/users/**}、{@code /api/design/**}、兜底 {@code /api/**}），
     * 因此清单里 {@code /actuator/**}、{@code /swagger-ui/**}、{@code /v3/api-docs/**}、
     * {@code /doc.html}、{@code /webjars/**} 这些<strong>今天不匹配任何路由</strong>，
     * 列与不列行为完全一致（它们由网关自己的 handler 服务，压根不经过鉴权过滤器）。
     * 保留它们是为将来给 API 文档加路由时备用 —— 但如果哪天加了路由而不复查这份清单，
     * 这些条目就会一夜之间从「无效」变成「真的放开」。
     *
     * <p>当前真正生效的是：{@code /api/auth/login}、{@code /api/auth/register}、
     * {@code /api/auth/captcha/send}、{@code /api/auth/password/reset}（走 auth 路由）、
     * {@code /api/system/algorithm-health}（走兜底路由到单体）。
     */
    private List<String> publicPaths = new ArrayList<>();

    /**
     * 是否把 {@code Authorization} 继续透传给下游。
     *
     * <p>默认 {@code false}：规范要求该头「仅网关解析、不透传」。下游服务里若还留着
     * 能读到它的代码（例如日志、异常信息、审计），一个一直往里透传的凭据就是持续的信息泄漏面。
     * 只有需要临时回退到阶段 0 行为时才置为 {@code true}。
     */
    private boolean forwardAuthorization = false;

    /**
     * 网关注入给下游服务的内部口令（{@code X-Internal-Token}）。
     *
     * <p>下游据此判断「这个请求确实经过网关」，从而敢于信任 {@code X-User-Id} 等身份头。
     * 未配置时网关<strong>不注入</strong>该头（而不是注入空值），下游因本地口令
     * 与请求头都无内容而 fail-closed 地忽略全部身份头 —— 结果是受保护路径一律 401，
     * 且日志里会留下「收到未经网关的身份头」的告警，便于定位是漏配而不是被攻击。
     */
    private String internalToken;
}
