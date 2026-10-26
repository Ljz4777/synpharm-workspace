package com.synpharm.gateway.security;

/**
 * 已验签的 Token 内容。
 *
 * @param userId  用户 ID（JWT {@code sub}）
 * @param email   用户邮箱（claim {@code email}）
 * @param role    角色（claim {@code role}）
 * @param tokenId Token 唯一标识（标准 claim {@code jti}，用于黑名单）
 * @author SynPharm Team
 */
public record ParsedToken(Long userId, String email, String role, String tokenId) {
}
