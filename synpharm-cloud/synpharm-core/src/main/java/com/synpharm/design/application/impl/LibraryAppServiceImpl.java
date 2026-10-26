package com.synpharm.design.application.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.synpharm.common.error.ErrorCode;
import com.synpharm.common.exception.BizException;
import com.synpharm.common.security.CurrentUser;
import com.synpharm.common.support.JsonCodec;
import com.synpharm.common.web.PageResult;
import com.synpharm.design.api.dto.LibraryItemNodeResponse;
import com.synpharm.design.api.dto.LibraryItemResponse;
import com.synpharm.design.api.dto.LibraryItemUpsertRequest;
import com.synpharm.design.application.LibraryAppService;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.domain.model.LibraryCategory;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.support.LikePatterns;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 知识库用例实现。
 *
 * <p>物化路径约定（与种子数据一致）：根节点 {@code /CATEGORY/CODE}，子节点
 * {@code 父.path + "/" + 本.code}，{@code level_no} 从 1 开始。
 *
 * @author SynPharm Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LibraryAppServiceImpl implements LibraryAppService {

    /** 关键词短于等于该长度时走「包含」匹配；更长时走前缀匹配以吃到 uk_category_code */
    private static final int KEYWORD_PREFIX_THRESHOLD = 3;

    private final DesignLibraryItemMapper libraryItemMapper;
    private final JsonCodec jsonCodec;

    @Override
    public PageResult<LibraryItemResponse> list(String category, Long parentId, String keyword, Boolean enabled,
                                                int page, int pageSize) {
        requireLogin();
        LibraryCategory cat = LibraryCategory.parse(category);

        LambdaQueryWrapper<DesignLibraryItem> query = Wrappers.lambdaQuery(DesignLibraryItem.class)
                .eq(DesignLibraryItem::getCategory, cat.name())
                .eq(parentId != null, DesignLibraryItem::getParentId, parentId)
                .eq(enabled != null, DesignLibraryItem::getEnabled, toFlag(enabled))
                .orderByAsc(DesignLibraryItem::getLevelNo)
                .orderByAsc(DesignLibraryItem::getSortNo)
                .orderByAsc(DesignLibraryItem::getId);
        applyKeyword(query, keyword);

        Page<DesignLibraryItem> result = libraryItemMapper.selectPage(new Page<>(page, pageSize), query);
        return PageResult.of(result, this::toResponse);
    }

    @Override
    public List<LibraryItemNodeResponse> tree(String category, boolean enabledOnly) {
        requireLogin();
        LibraryCategory cat = LibraryCategory.parse(category);

        List<DesignLibraryItem> all = libraryItemMapper.selectList(
                Wrappers.lambdaQuery(DesignLibraryItem.class)
                        .eq(DesignLibraryItem::getCategory, cat.name())
                        .eq(enabledOnly, DesignLibraryItem::getEnabled, 1)
                        .orderByAsc(DesignLibraryItem::getLevelNo)
                        .orderByAsc(DesignLibraryItem::getSortNo)
                        .orderByAsc(DesignLibraryItem::getId));

        Map<Long, LibraryItemNodeResponse> nodes = new LinkedHashMap<>();
        for (DesignLibraryItem item : all) {
            nodes.put(item.getId(), toNode(item));
        }

        List<LibraryItemNodeResponse> roots = new ArrayList<>();
        for (DesignLibraryItem item : all) {
            LibraryItemNodeResponse node = nodes.get(item.getId());
            LibraryItemNodeResponse parent = item.getParentId() == null ? null : nodes.get(item.getParentId());
            if (parent == null) {
                // 父级被 enabledOnly 过滤掉时把子项提升为根，否则整棵子树会凭空消失
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    @Override
    public LibraryItemResponse get(Long id) {
        requireLogin();
        return toResponse(loadOrThrow(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LibraryItemResponse create(LibraryItemUpsertRequest request) {
        LibraryCategory category = LibraryCategory.parse(request.getCategory());
        assertCodeAvailable(category, request.getCode(), null);
        DesignLibraryItem parent = resolveParent(category, request.getParentId(), null);

        DesignLibraryItem entity = new DesignLibraryItem();
        applyUpsert(entity, request, category, parent, true);

        try {
            libraryItemMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.LIBRARY_CODE_DUPLICATED, null,
                    Map.of("category", category.name(), "code", request.getCode()));
        }
        return toResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LibraryItemResponse update(Long id, LibraryItemUpsertRequest request) {
        DesignLibraryItem entity = loadOrThrow(id);
        LibraryCategory category = LibraryCategory.parse(request.getCategory());

        if (isTrue(entity.getBuiltin())) {
            assertBuiltinUpdateAllowed(entity, request, category);
            if (request.getEnabled() != null) {
                entity.setEnabled(toFlag(request.getEnabled()));
            }
            if (request.getSortNo() != null) {
                entity.setSortNo(request.getSortNo());
            }
            entity.setRemark(request.getRemark());
            libraryItemMapper.updateById(entity);
            return toResponse(entity);
        }

        assertCodeAvailable(category, request.getCode(), id);
        DesignLibraryItem parent = resolveParent(category, request.getParentId(), entity);

        String oldPath = entity.getPath();
        int oldLevel = entity.getLevelNo() == null ? 1 : entity.getLevelNo();
        applyUpsert(entity, request, category, parent, false);

        try {
            libraryItemMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.LIBRARY_CODE_DUPLICATED, null,
                    Map.of("category", category.name(), "code", request.getCode()));
        }

        // 换父或改名会改变自身路径，子树必须整体平移，否则 path 前缀匹配（子树查询）会错位
        boolean moved = !Objects.equals(oldPath, entity.getPath())
                || !Objects.equals(oldLevel, entity.getLevelNo());
        if (moved && StringUtils.hasText(oldPath)) {
            int delta = (entity.getLevelNo() == null ? 1 : entity.getLevelNo()) - oldLevel;
            int affected = libraryItemMapper.updateSubtreePath(category.name(), oldPath,
                    LikePatterns.escape(oldPath), entity.getPath(), delta);
            if (affected > 0) {
                log.info("知识项 {} 迁移，子树同步 {} 行（{} -> {}）", id, affected, oldPath, entity.getPath());
            }
        }
        return toResponse(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        loadOrThrow(id);

        Long children = libraryItemMapper.selectCount(
                Wrappers.lambdaQuery(DesignLibraryItem.class)
                        .eq(DesignLibraryItem::getParentId, id));
        if (children != null && children > 0) {
            throw new BizException(ErrorCode.LIBRARY_HAS_CHILDREN, null, Map.of("id", id, "children", children));
        }
        // @TableLogic 把 deleteById 改写成 deleted = 1
        libraryItemMapper.deleteById(id);
    }

    // ------------------------------------------------------------------ 内部

    private void applyKeyword(LambdaQueryWrapper<DesignLibraryItem> query, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return;
        }
        String kw = keyword.trim();
        boolean prefix = kw.length() > KEYWORD_PREFIX_THRESHOLD;
        query.and(w -> {
            if (prefix) {
                w.likeRight(DesignLibraryItem::getCode, kw)
                        .or().likeRight(DesignLibraryItem::getName, kw)
                        .or().likeRight(DesignLibraryItem::getNameEn, kw);
            } else {
                w.like(DesignLibraryItem::getCode, kw)
                        .or().like(DesignLibraryItem::getName, kw)
                        .or().like(DesignLibraryItem::getNameEn, kw);
            }
        });
    }

    private DesignLibraryItem loadOrThrow(Long id) {
        DesignLibraryItem entity = libraryItemMapper.selectById(id);
        if (entity == null) {
            throw new BizException(ErrorCode.LIBRARY_ITEM_NOT_FOUND, null, Map.of("id", id));
        }
        return entity;
    }

    /** 同分类 code 预检：命中唯一键 uk_category_code 时给前端一个明确的 409 */
    private void assertCodeAvailable(LibraryCategory category, String code, Long excludeId) {
        Long count = libraryItemMapper.selectCount(
                Wrappers.lambdaQuery(DesignLibraryItem.class)
                        .eq(DesignLibraryItem::getCategory, category.name())
                        .eq(DesignLibraryItem::getCode, code)
                        .ne(excludeId != null, DesignLibraryItem::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.LIBRARY_CODE_DUPLICATED, null,
                    Map.of("category", category.name(), "code", code));
        }
    }

    /**
     * 解析并校验父级。
     *
     * @param self 更新场景下的自身实体（用于环检测）；新建传 {@code null}
     */
    private DesignLibraryItem resolveParent(LibraryCategory category, Long parentId, DesignLibraryItem self) {
        if (parentId == null) {
            return null;
        }
        if (self != null && parentId.equals(self.getId())) {
            throw new BizException(ErrorCode.LIBRARY_PARENT_INVALID, "父级不能是自身",
                    Map.of("parentId", parentId));
        }
        DesignLibraryItem parent = libraryItemMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(ErrorCode.LIBRARY_PARENT_INVALID, "父级不存在", Map.of("parentId", parentId));
        }
        if (!category.name().equals(parent.getCategory())) {
            throw new BizException(ErrorCode.LIBRARY_PARENT_INVALID, "父级与当前分类不一致",
                    Map.of("parentId", parentId, "parentCategory", parent.getCategory()));
        }
        // 新父级落在自身子树内 = 成环
        if (self != null && StringUtils.hasText(self.getPath()) && StringUtils.hasText(parent.getPath())
                && parent.getPath().startsWith(self.getPath() + "/")) {
            throw new BizException(ErrorCode.LIBRARY_PARENT_INVALID, "父级是自身的子节点，会造成环",
                    Map.of("parentId", parentId));
        }
        return parent;
    }

    /**
     * 把请求写入实体。
     *
     * <p>{@code metaJson} / {@code sortNo} / {@code enabled} 在契约里都是**非必填**，
     * 于是「没传」必须有确定语义：新建时取默认值，更新时**保持原值不变**。
     * 若更新时把 null 理解成「清空」，那么只想改 remark 的调用方会顺手把 meta 抹掉 ——
     * 而内置项保护又按「未变更」放行，两处判断就自相矛盾了。
     *
     * @param isCreate true=新建（缺省值生效），false=更新（缺省=不变）
     */
    private void applyUpsert(DesignLibraryItem entity, LibraryItemUpsertRequest request,
                             LibraryCategory category, DesignLibraryItem parent, boolean isCreate) {
        entity.setCategory(category.name());
        entity.setCode(request.getCode());
        entity.setName(request.getName());
        entity.setNameEn(request.getNameEn());
        entity.setParentId(parent == null ? null : parent.getId());
        entity.setRemark(request.getRemark());

        if (request.getMetaJson() != null) {
            entity.setMetaJson(jsonCodec.write(request.getMetaJson()));
        } else if (isCreate) {
            entity.setMetaJson(null);
        }
        if (request.getSortNo() != null) {
            entity.setSortNo(request.getSortNo());
        } else if (isCreate) {
            entity.setSortNo(0);
        }
        if (request.getEnabled() != null) {
            entity.setEnabled(toFlag(request.getEnabled()));
        } else if (isCreate) {
            entity.setEnabled(1);
        }

        if (parent == null) {
            entity.setLevelNo(1);
            entity.setPath("/" + category.name() + "/" + request.getCode());
            if (isCreate) {
                entity.setBuiltin(0);
            }
        } else {
            entity.setLevelNo((parent.getLevelNo() == null ? 1 : parent.getLevelNo()) + 1);
            String parentPath = StringUtils.hasText(parent.getPath())
                    ? parent.getPath()
                    : "/" + category.name() + "/" + parent.getCode();
            entity.setPath(parentPath + "/" + request.getCode());
        }
    }

    /** 内置项只允许改 enabled / sortNo / remark，其余字段一旦与现值不同即拒绝 */
    private void assertBuiltinUpdateAllowed(DesignLibraryItem current, LibraryItemUpsertRequest request,
                                           LibraryCategory category) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(current.getCategory(), category.name())) {
            changed.add("category");
        }
        if (!Objects.equals(current.getCode(), request.getCode())) {
            changed.add("code");
        }
        if (!Objects.equals(current.getName(), request.getName())) {
            changed.add("name");
        }
        if (!Objects.equals(current.getNameEn(), request.getNameEn())) {
            changed.add("nameEn");
        }
        if (!Objects.equals(current.getParentId(), request.getParentId())) {
            changed.add("parentId");
        }
        if (request.getMetaJson() != null
                && !Objects.equals(jsonCodec.readMap(current.getMetaJson()), request.getMetaJson())) {
            changed.add("metaJson");
        }
        if (!changed.isEmpty()) {
            throw new BizException(ErrorCode.LIBRARY_BUILTIN_IMMUTABLE, null,
                    Map.of("id", current.getId(), "fields", changed,
                            "allowedFields", List.of("enabled", "sortNo", "remark")));
        }
    }

    private LibraryItemResponse toResponse(DesignLibraryItem entity) {
        LibraryItemResponse response = new LibraryItemResponse();
        fill(response, entity);
        return response;
    }

    private LibraryItemNodeResponse toNode(DesignLibraryItem entity) {
        LibraryItemNodeResponse node = new LibraryItemNodeResponse();
        fill(node, entity);
        return node;
    }

    private void fill(LibraryItemResponse target, DesignLibraryItem entity) {
        target.setId(entity.getId());
        target.setCategory(entity.getCategory());
        target.setCode(entity.getCode());
        target.setName(entity.getName());
        target.setNameEn(entity.getNameEn());
        target.setParentId(entity.getParentId());
        target.setLevelNo(entity.getLevelNo());
        target.setPath(entity.getPath());
        target.setMeta(jsonCodec.readMap(entity.getMetaJson()));
        target.setEnabled(isTrue(entity.getEnabled()));
        target.setBuiltin(isTrue(entity.getBuiltin()));
        target.setSortNo(entity.getSortNo());
        target.setRemark(entity.getRemark());
    }

    /**
     * 要求已登录。
     *
     * <p>知识库是全平台共享的参考数据（表里没有属主列），所以**不做归属校验**，但仍须确认
     * 调用方有身份 —— 这是问题总账 H-27 的整改：网关只对 `/api/design/**` 验签，
     * **同网络内绕过网关直连服务不带身份头**，若服务自己不校验，这类端点就是完全开放的
     * （实测过直连 `http://design:8083/api/design/library` 返回 200）。
     *
     * <p>写在每个读方法的第一行，而不是用注解/拦截器：这三个方法是本控制器对外的全部读取面，
     * 显式列出比隐式切面更容易在评审时发现"漏了一个"。
     */
    private static void requireLogin() {
        CurrentUser.requireUserId();
    }

    private static int toFlag(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private static boolean isTrue(Integer flag) {
        return flag != null && flag == 1;
    }
}
