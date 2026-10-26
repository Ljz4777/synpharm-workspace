package com.synpharm.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SynPharm 主业务服务（core）。
 *
 * <p>本服务由原 {@code synpharm-design} / {@code synpharm-asset} / {@code synpharm-platform}
 * 三个模块合并而来，持有 {@code synpharm_core} schema（设计域 19 + 资产 1 + 工具 1 = 21 张表），
 * 是这些表<b>唯一</b>的写入方。
 *
 * <p><b>为什么合并</b>：主业务四段（需求分析 → 生成 → 编辑 → 决策）全部落在设计域，asset 与
 * platform 本就是它的支撑件。原来的拆分导致唯一一次跨服务同步调用（design → platform 工具注册表），
 * 以及一条必须前置的网关路由（{@code platform-design-tools} 必须排在 {@code design} 之前，
 * 否则 {@code /api/design/tools/**} 被静默吃掉成 404）。合并后两者都消失。
 *
 * <p><b>保留独立</b>：gateway（网关）、auth（身份）、predict（预测域）仍为独立服务 ——
 * 它们属于基础套件，有独立的部署与配置边界。
 *
 * <p>鉴权边界：本服务<b>不</b>校验 JWT —— 网关已验签并注入 {@code X-User-Id} / {@code X-User-Role}。
 * 防伪造由 {@link com.synpharm.common.security.InternalRequestFilter} + 内部令牌负责。
 *
 * @author SynPharm Team
 */
// 扫描 com.synpharm：公共库（synpharm-common）里的过滤器 / 全局异常处理器 / MybatisPlusConfig 才会装配
// 同时也覆盖 com.synpharm.design / asset / platform 三个子包
@SpringBootApplication(scanBasePackages = "com.synpharm")
@EnableDiscoveryClient
// platform 的能力矩阵定时探测（红线 R1：design_tool.available 的唯一数据源）
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan({
        "com.synpharm.design.infrastructure.persistence",
        "com.synpharm.asset.infrastructure.persistence",
        "com.synpharm.platform.infrastructure.persistence"
})
public class SynpharmCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynpharmCoreApplication.class, args);
    }
}
