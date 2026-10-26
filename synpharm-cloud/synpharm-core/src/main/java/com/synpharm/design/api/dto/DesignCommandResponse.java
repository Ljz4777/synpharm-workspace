package com.synpharm.design.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 命令注册表条目（契约 `GET /api/design/commands`）。
 *
 * <p>数据源是 {@code design_tool}（经 platform 的内网接口），因此字段做了如下映射：
 * {@code id ← toolCode}、{@code label ← name}、{@code group ← category}、
 * {@code available ← design_tool.available}、{@code reason ← probe_msg}。
 *
 * <p><b>{@code writes} 的推导</b>：{@code design_tool} 表里没有"是否写操作"这一列，
 * 契约（openapi）对该端点的响应只写了 `OkObject` 没有细化 schema，而前端
 * {@code DesignCommand} 需要它。这里按「**检索类不算写操作，其余都要经命令注册表改状态**」
 * 推导（即 {@code category != RETRIEVAL}）。这是实现补齐的约定，改动前请先确认
 * {@code design_tool} 是否新增了权威字段。
 *
 * @author SynPharm Team
 */
@Data
@Builder
public class DesignCommandResponse {

    private String id;

    private String label;

    private String group;

    /** 是否为写操作（写操作必须经命令注册表执行） */
    private Boolean writes;

    /** 当前是否可用（引擎未部署 / 工具被禁用 / 项目状态不允许） */
    private Boolean available;

    /** 不可用原因，UI 直接展示（红线 R2：不得"点了才失败"） */
    private String reason;

    /** 真实性边界，命令面板据此提示（红线 R1） */
    private String realness;
}
