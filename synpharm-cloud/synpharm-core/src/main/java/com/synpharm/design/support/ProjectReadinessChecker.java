package com.synpharm.design.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.design.domain.model.DesignProject;
import com.synpharm.design.domain.model.ProjectTarget;
import com.synpharm.design.domain.model.Target;
import com.synpharm.design.infrastructure.persistence.ProjectTargetMapper;
import com.synpharm.design.infrastructure.persistence.TargetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目就绪度判定（红线 R2：**不得静默就绪**）。
 *
 * <p>{@code READY} 是推导态，且是「开轮次」的前置。判定规则必须只有一份 ——
 * 否则项目详情说「就绪」、开轮次却报「未就绪」，用户完全无从下手。
 *
 * <p>当前规则：已绑定靶点 → 恰好一个主靶点 → 主靶点有序列 → 已绑定生效约束集。
 *
 * @author SynPharm Team
 */
@Component
@RequiredArgsConstructor
public class ProjectReadinessChecker {

    private final ProjectTargetMapper projectTargetMapper;
    private final TargetMapper targetMapper;

    /**
     * 收集「进入 READY 还缺什么」。
     *
     * @return 空列表表示已就绪
     */
    public List<String> blockers(DesignProject project) {
        List<String> blockers = new ArrayList<>();
        List<ProjectTarget> bindings = projectTargetMapper.selectList(Wrappers.lambdaQuery(ProjectTarget.class)
                .eq(ProjectTarget::getProjectId, project.getId())
                .orderByAsc(ProjectTarget::getSortNo));

        if (bindings.isEmpty()) {
            blockers.add("尚未绑定靶点");
        } else {
            ProjectTarget primary = bindings.stream()
                    .filter(b -> Integer.valueOf(1).equals(b.getIsPrimary()))
                    .findFirst()
                    .orElse(null);
            if (primary == null) {
                blockers.add("未指定主靶点（须恰好一个 isPrimary=true）");
            } else {
                Target target = targetMapper.selectById(primary.getTargetId());
                String label = target == null ? ("#" + primary.getTargetId()) : target.getName();
                if (target == null || !StringUtils.hasText(target.getSequence())) {
                    blockers.add("主靶点缺少序列：" + label);
                }
            }
        }
        if (project.getActiveConstraintSetId() == null) {
            blockers.add("尚未激活约束集");
        }
        return blockers;
    }
}
