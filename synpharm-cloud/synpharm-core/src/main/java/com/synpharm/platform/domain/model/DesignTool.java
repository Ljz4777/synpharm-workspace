package com.synpharm.platform.domain.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * design_tool · 工具注册表（含 realness 真实性边界与「当前真实可用性」）。
 *
 * <p>由 synpharm_platform schema 生成。本表归 synpharm-platform 独占（拆分铁律 R2）——
 * design / predict 需要工具元数据时**走接口拿**，不直连本库。
 *
 * <p>两条易误读的语义，改动前务必先读：
 * <ol>
 *   <li>{@code available} 默认 <b>0</b>：建表默认值刻意不是 1，否则会被读成「可用」。
 *       真实可用性由能力探测（probe）写入，未探测过就必须保持 0 并配 {@code probeMsg} 说明原因。</li>
 *   <li>{@code realness} 是**真实性边界**（REAL / SEMI_REAL / HEURISTIC / SIMULATED），
 *       报告与评估要据此标注，不能被前端当作装饰字段丢掉。</li>
 * </ol>
 *
 * @author SynPharm Team
 */
@Data
@TableName("design_tool")
public class DesignTool {

    /** 工具ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 工具码（唯一） */
    private String toolCode;

    /** 工具名 */
    private String name;

    /** 说明 */
    private String description;

    /** 分类 */
    private String category;

    /** 调用类型：HTTP / LOCAL / MQ */
    private String type;

    /** 提供方：FASTAPI / INTERNAL */
    private String provider;

    /** 端点路径 */
    private String endpoint;

    /** HTTP 方法 */
    private String httpMethod;

    /** 入参 JSON Schema */
    private String inputSchema;

    /** 出参 JSON Schema */
    private String outputSchema;

    /** 是否异步执行 */
    private Integer asyncExecution;

    /** 超时毫秒 */
    private Integer timeoutMs;

    /** 依赖的引擎标识（能力探测用） */
    private String requiresEngine;

    /** 能力矩阵键 */
    private String capabilityKey;

    /** REAL/SEMI_REAL/HEURISTIC/SIMULATED */
    private String realness;

    /** 是否启用（管理员开关） */
    private Integer enabled;

    /** 当前真实可用性（探测结果；未探测过必须为 0） */
    private Integer available;

    /** 不可用原因（前端直接展示；红线 R2：能力不足必须可见） */
    private String probeMsg;

    /** 最后一次探测时间 */
    private LocalDateTime lastProbeAt;

    /** 是否暴露给 agent */
    private Integer agentExposed;

    /** agent 工具名 */
    private String agentToolName;

    /** 版本 */
    private String version;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;
}
