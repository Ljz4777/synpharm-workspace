package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.BindingPocketResponse;
import com.synpharm.design.api.dto.TargetResponse;
import com.synpharm.design.api.dto.TargetStructureResponse;
import com.synpharm.design.application.TargetAppService;
import com.synpharm.design.domain.model.BindingPocket;
import com.synpharm.design.domain.model.Target;
import com.synpharm.design.domain.model.TargetStatus;
import com.synpharm.design.domain.model.TargetStructure;
import com.synpharm.design.infrastructure.persistence.BindingPocketMapper;
import com.synpharm.design.infrastructure.persistence.TargetMapper;
import com.synpharm.design.infrastructure.persistence.TargetStructureMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 靶点库读路径实现。
 *
 * <p><b>子项一律批量加载，不做逐行查询</b>（与约束集列表同一套路）：先取一页靶点，
 * 再用 `IN` 一次取这批靶点的全部结构，再一次取这批结构的全部口袋 —— 无论页大小，
 * 固定 3 条 SQL。逐行查会退化成 1+N+N*M 次往返。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TargetAppServiceImpl implements TargetAppService {

    /** 关键词短于等于该长度时走「包含」匹配；更长时走前缀匹配，与知识库检索保持同一阈值 */
    private static final int KEYWORD_PREFIX_THRESHOLD = 3;

    private final TargetMapper targetMapper;
    private final TargetStructureMapper targetStructureMapper;
    private final BindingPocketMapper bindingPocketMapper;
    private final JsonCodec jsonCodec;

    @Override
    public PageResult<TargetResponse> list(String keyword, String targetType, String diseaseArea,
                                           String status, int page, int pageSize) {
        requireLogin();
        // status 是契约声明的封闭枚举：非法值必须报 400。若静默忽略，
        // 用户拿到的是**没筛过的全量列表**却以为筛过了
        TargetStatus parsedStatus = StringUtils.hasText(status) ? TargetStatus.parse(status) : null;

        LambdaQueryWrapper<Target> query = Wrappers.lambdaQuery(Target.class)
                .eq(parsedStatus != null, Target::getStatus,
                        parsedStatus == null ? null : parsedStatus.name().toLowerCase())
                .eq(StringUtils.hasText(targetType), Target::getTargetType, trimToNull(targetType))
                .eq(StringUtils.hasText(diseaseArea), Target::getDiseaseArea, trimToNull(diseaseArea))
                .orderByAsc(Target::getId);
        applyKeyword(query, keyword);

        Page<Target> result = targetMapper.selectPage(new Page<>(page, pageSize), query);

        List<Long> targetIds = result.getRecords().stream().map(Target::getId).toList();
        Map<Long, List<TargetStructureResponse>> structuresByTarget = loadStructures(targetIds);
        Map<Long, List<BindingPocketResponse>> pocketsByStructure = loadPockets(
                structuresByTarget.values().stream()
                        .flatMap(List::stream)
                        .map(TargetStructureResponse::getId)
                        .toList());

        return PageResult.of(result, target -> {
            TargetResponse response = toResponse(target);
            response.setStructures(structuresByTarget.getOrDefault(target.getId(), List.of()).stream()
                    .map(structure -> withPockets(structure, pocketsByStructure))
                    .toList());
            return response;
        });
    }

    @Override
    public TargetResponse get(Long id) {
        requireLogin();
        Target target = id == null ? null : targetMapper.selectById(id);
        if (target == null) {
            throw new BizException(ErrorCode.TARGET_NOT_FOUND, "靶点不存在：" + id);
        }

        List<TargetStructureResponse> structures = loadStructures(List.of(id)).getOrDefault(id, List.of());
        Map<Long, List<BindingPocketResponse>> pockets = loadPockets(
                structures.stream().map(TargetStructureResponse::getId).toList());

        TargetResponse response = toResponse(target);
        response.setStructures(structures.stream().map(structure -> withPockets(structure, pockets)).toList());
        return response;
    }

    @Override
    public List<BindingPocketResponse> listPockets(Long structureId) {
        requireLogin();
        TargetStructure structure = structureId == null ? null : targetStructureMapper.selectById(structureId);
        if (structure == null) {
            // 不能返回 200 + 空数组：那等于告诉前端「这个结构一个口袋都没有」，
            // 而事实是这个结构根本不存在（契约只登记了 200，码见规范 §4.4 补登记说明）
            throw new BizException(ErrorCode.STRUCTURE_NOT_FOUND, "结构不存在：" + structureId);
        }
        return loadPockets(List.of(structureId)).getOrDefault(structureId, List.of());
    }

    // ------------------------------------------------------------------ 子项批量加载

    /** 按靶点批量取结构，按 targetId 分组 */
    private Map<Long, List<TargetStructureResponse>> loadStructures(List<Long> targetIds) {
        if (targetIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<TargetStructure> structures = targetStructureMapper.selectList(
                Wrappers.lambdaQuery(TargetStructure.class)
                        .in(TargetStructure::getTargetId, targetIds)
                        // 默认结构排在前，其余按 PDB ID 稳定排序（避免翻页/刷新顺序抖动）
                        .orderByDesc(TargetStructure::getIsPreferred)
                        .orderByAsc(TargetStructure::getId));
        return structures.stream()
                .collect(Collectors.groupingBy(TargetStructure::getTargetId,
                        Collectors.mapping(this::toResponse, Collectors.toList())));
    }

    /** 按结构批量取口袋，按 structureId 分组 */
    private Map<Long, List<BindingPocketResponse>> loadPockets(List<Long> structureIds) {
        if (structureIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<BindingPocket> pockets = bindingPocketMapper.selectList(
                Wrappers.lambdaQuery(BindingPocket.class)
                        .in(BindingPocket::getStructureId, structureIds)
                        .orderByAsc(BindingPocket::getStructureId)
                        .orderByAsc(BindingPocket::getPocketNo));
        return pockets.stream()
                .collect(Collectors.groupingBy(BindingPocket::getStructureId,
                        Collectors.mapping(this::toResponse, Collectors.toList())));
    }

    /** 把已加载的口袋挂到结构上（列表与详情共用，保证两处形状一致） */
    private static TargetStructureResponse withPockets(TargetStructureResponse structure,
                                                       Map<Long, List<BindingPocketResponse>> pockets) {
        structure.setPockets(pockets.getOrDefault(structure.getId(), List.of()));
        return structure;
    }

    // ------------------------------------------------------------------------ 映射

    private TargetResponse toResponse(Target entity) {
        TargetResponse response = new TargetResponse();
        response.setId(entity.getId());
        response.setUniprotId(entity.getUniprotId());
        response.setName(entity.getName());
        response.setChineseName(entity.getChineseName());
        response.setGeneName(entity.getGeneName());
        response.setOrganism(entity.getOrganism());
        response.setTargetType(entity.getTargetType());
        response.setFamily(entity.getFamily());
        response.setDiseaseArea(entity.getDiseaseArea());
        response.setDescription(entity.getDescription());
        response.setPathway(entity.getPathway());
        response.setRelatedDiseases(entity.getRelatedDiseases());
        response.setKnownDrugs(entity.getKnownDrugs());
        response.setSequence(entity.getSequence());
        response.setSequenceLength(entity.getSequenceLength());
        response.setPdbIds(entity.getPdbIds());
        // 库里的值就是契约的小写取值；不做大小写转换，避免把脏数据"修好"后更难发现
        response.setStatus(entity.getStatus());
        return response;
    }

    private TargetStructureResponse toResponse(TargetStructure entity) {
        TargetStructureResponse response = new TargetStructureResponse();
        response.setId(entity.getId());
        response.setTargetId(entity.getTargetId());
        response.setPdbId(entity.getPdbId());
        response.setChainId(entity.getChainId());
        response.setSource(entity.getSource());
        response.setSourceKey(entity.getSourceKey());
        response.setExperimentMethod(entity.getExperimentMethod());
        response.setResolution(entity.getResolution());
        response.setLigandCount(entity.getLigandCount());
        response.setPocketCount(entity.getPocketCount());
        response.setIsPreferred(isTrue(entity.getIsPreferred()));
        response.setFileAssetId(entity.getFileAssetId());
        return response;
    }

    private BindingPocketResponse toResponse(BindingPocket entity) {
        BindingPocketResponse response = new BindingPocketResponse();
        response.setId(entity.getId());
        response.setStructureId(entity.getStructureId());
        response.setPocketNo(entity.getPocketNo());
        response.setMethod(entity.getMethod());
        response.setScore(entity.getScore());
        response.setDruggability(entity.getDruggability());
        response.setVolume(entity.getVolume());
        response.setCenterX(entity.getCenterX());
        response.setCenterY(entity.getCenterY());
        response.setCenterZ(entity.getCenterZ());
        response.setResidues(readResidues(entity.getResiduesJson()));
        response.setIsSelected(isTrue(entity.getIsSelected()));
        response.setNote(entity.getNote());
        return response;
    }

    /**
     * 解码 {@code residues_json}。
     *
     * <p>用 {@code Map[].class} 复用 {@link JsonCodec#read} 而不是给共享库加一个
     * 「按 TypeReference 读 List」的重载：改共享库要刷新全部服务的 m2 依赖并逐个重启
     * （问题总账 H-21），而这个数组形态足够表达契约里的 {@code residues}。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readResidues(String json) {
        Map<String, Object>[] residues = jsonCodec.read(json, Map[].class);
        return residues == null ? List.of() : new ArrayList<>(List.of(residues));
    }

    private void applyKeyword(LambdaQueryWrapper<Target> query, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return;
        }
        String kw = keyword.trim();
        // 规范 §4.4：超 3 字符走前缀匹配（能吃上索引），否则才退化为包含匹配
        boolean prefix = kw.length() > KEYWORD_PREFIX_THRESHOLD;
        query.and(w -> {
            if (prefix) {
                w.likeRight(Target::getUniprotId, kw)
                        .or().likeRight(Target::getName, kw)
                        .or().likeRight(Target::getChineseName, kw)
                        .or().likeRight(Target::getGeneName, kw);
            } else {
                w.like(Target::getUniprotId, kw)
                        .or().like(Target::getName, kw)
                        .or().like(Target::getChineseName, kw)
                        .or().like(Target::getGeneName, kw);
            }
        });
    }

    /**
     * 要求已登录。
     *
     * <p>靶点是共享参考数据（表里没有属主列），所以**不做归属校验**，但仍然必须确认调用方
     * 是有身份的会话。这一条不是冗余：网关只对 `/api/design/**` 做验签，**同网络内绕过网关
     * 直连服务**是不带身份头的，若服务自己不校验，这类端点在服务层面就是完全开放的
     * ——实测确认过：不加这一句时直连 `http://design:8083/api/design/targets` 返回 **200**。
     *
     * @see com.synpharm.common.security.CurrentUser#requireUserId()
     */
    private static void requireLogin() {
        CurrentUser.requireUserId();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static boolean isTrue(Integer flag) {
        return flag != null && flag == 1;
    }
}
