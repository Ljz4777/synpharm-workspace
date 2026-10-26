package com.synpharm.design.domain.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * candidate_molecule · 候选分子。
 *
 * <p>由 synpharm_design schema 生成；字段注释与数据库列注释一致。
 * 本表归 synpharm-design 独占，其他服务不得直连（拆分铁律 R2）。
 *
 * @author SynPharm Team
 */
@Data
@TableName("candidate_molecule")
public class CandidateMolecule {

    /** bigint，非空 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 冗余，便于按项目查询 */
    private Long projectId;

    /** 产生于哪一轮（编辑产物记为此轮） */
    private Long roundId;

    /** 若由生成产生，指向 generation_run */
    private Long originRunId;

    /** 标准化后的 SMILES；生物大分子为 NULL */
    private String smiles;

    /** 标准化 InChIKey，小分子去重键；生物大分子为 NULL */
    private String inchikey;

    /** RDKit canonical（展示用） */
    private String canonicalSmiles;

    /** 可选名称 */
    private String moleculeName;

    /** GENERATED/EDITED/IMPORTED/SEED */
    private String originType;

    /** SMALL_MOLECULE/ANTIBODY/PEPTIDE/BISPECIFIC/ADC/FUSION_PROTEIN */
    private String modalityCode;

    /** 生成策略名（origin_type=GENERATED 时） */
    private String strategyName;

    /** 编辑来源的父候选（origin_type=EDITED 时） */
    private Long parentMolId;

    /** 该分子的生成参数，保证可复现（红线 R5） */
    private String generationParams;

    /** 分子量（冗余，便于排序） */
    private BigDecimal mw;

    /** LogP（冗余，便于排序） */
    private BigDecimal logp;

    /** 合成可及性（冗余） */
    private BigDecimal saScore;

    /** QED（冗余） */
    private BigDecimal qed;

    /** 是否通过全部 hard 约束 */
    private Integer hardPassed;

    /** NEW/QUALIFIED/REJECTED/TOP/SELECTED */
    private String reviewStatus;

    /** 用户打的标签 */
    private String batchTag;

    /** 用户备注 */
    private String note;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer deleted;

    /** datetime，非空 */
    private LocalDateTime createdAt;

    /** datetime，非空 */
    private LocalDateTime updatedAt;

    /** 生物大分子序列（抗体/多肽/蛋白）；小分子为 NULL */
    private String sequence;

    /** sha256(规范化序列)，生物大分子去重键 */
    private String sequenceHash;

    /** 序列长度 */
    private Integer sequenceLength;

    /** 结构文件 URI（SDF/PDB，对象存储） */
    private String structureUri;

    /** 结构文件资产（file_asset.id） */
    private Long structureAssetId;

    /** 加权综合得分（06 评估器回写，仅用于排序） */
    private BigDecimal totalScore;

    // ---------------------------------------------------------------- 生成流水线（06 §4.4）

    /**
     * 生成流水线状态：pending/generating/generated/screening/passed/rejected/error/archived。
     *
     * <p>与 {@code reviewStatus} <b>刻意分开</b>：两者都有 {@code rejected}，
     * 但前者是机器筛掉、后者是人放弃。合并字段会让"这个分子是算出来不行，还是我们不想做"
     * 永久无法区分。
     */
    private String pipelineStatus;

    /** S7 簇标签 */
    private String clusterId;

    /** S6 粗筛得分（0~100，仅小分子有） */
    private BigDecimal preScore;

    /** 亚型，如 IgG1/IgG4（modality 之下一层） */
    private String moleculeSubtype;

    /** 重链（抗体） */
    private String heavyChain;

    /** 轻链（抗体） */
    private String lightChain;

    /** 额外链（双抗/ADC），JSON 数组 */
    private String extraChains;

    /** 结构来源方法：AF2_multimer_v3 / PDB / — */
    private String structureModelMethod;

    /** 结构置信度 0~1 */
    private BigDecimal structureConfidence;

    /**
     * 版本号：root = 1，每个子版本 = 父版本 + 1。
     *
     * <p>{@code evaluation_freeze} 与 {@code edit_delta_report} 都以
     * {@code (candidateId, versionNo)} 定位版本。原本只用 {@code parentMolId} 表达血缘，
     * 但每次靠递归推版本既慢又易错，故显式落列。
     */
    private Integer versionNo;

    /** 已编辑轮数（上限默认 5，阈值在基线快照） */
    private Integer editRound;

    /** active / abandoned。放弃的分支保留但不参与后续筛选 */
    private String branchStatus;

    /** 约束变更后置 1，表示冻结分数已不可信，需重评 */
    private Integer needsReEval;

    /** 分支横向可比的前提：同一评分配置 */
    private Long evalProfileId;

}
