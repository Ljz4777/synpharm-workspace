package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * molecule_edit_log · 分子编辑历史。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("molecule_edit_log")
public class MoleculeEditLog {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** bigint，非空 */
    private Long projectId;

    /** 编辑发生在哪一轮 */
    private Long roundId;

    /** 父候选；手工导入时为 NULL */
    private Long parentMolId;

    /** 编辑产物候选 */
    private Long childMolId;

    /** ADD_SUBSTITUENT/REMOVE_SUBSTITUENT/REPLACE_GROUP/RING_OPEN_CLOSE/CHAIN_EXTEND/HETERO_SWAP/STEREO_TUNE/RAW_SMILES/FRAGMENT_JOIN */
    private String editType;

    /** SIDE_CHAIN/SCAFFOLD_HOP/MUTATION/LINKER/INTERFACE_REDESIGN/HUMANIZATION/GLYCO_OPT */
    private String strategyCode;

    /** 作用区域/位点：CDR-H3 / 界面残基 745 / R 基位置 C12 */
    private String targetRegion;

    /** 分子形式 */
    private String modalityCode;

    /** 操作参数，如 {"atomIdx":12,"group":"[OH]"} */
    private String paramsJson;

    /** 编辑前 SMILES（快照） */
    private String parentSmiles;

    /** 编辑后 SMILES（快照） */
    private String childSmiles;

    /** 发生变化的原子索引与前后值，供前端高亮 */
    private String changedAtoms;

    /** 人类可读描述，如"在 12 位 N 上添加羟基" */
    private String describeText;

    /** 化学合法性校验是否通过 */
    private Integer valid;

    /** varchar(500) */
    private String errorMsg;

    /** 谁改的（人 或 二阶段的智能体） */
    private Long operatorId;

    /** USER/AGENT（二阶段用） */
    private String operatorType;

    /** int */
    private Integer latencyMs;

    /** datetime，非空 */
    private LocalDateTime createdAt;

}
