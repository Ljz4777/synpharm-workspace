package com.synpharm.design.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.synpharm.design.domain.model.DesignLibraryItem;
import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.infrastructure.persistence.LibraryDatasetMapper;
import com.synpharm.design.infrastructure.persistence.TargetMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 基线种子数据存在性检查。
 *
 * <p>只读 count，不写库、不自动修复 —— 线上自动灌种子可能覆盖用户改过的 builtin 项。
 *
 * <p><b>为什么不用 metric_definition 的行数做判据：</b>基础指标由
 * {@code 22_metric_definition.sql} 建表时一并插入，即使 37 从未执行，
 * 这张表也非空。用它做探针会把「37 没跑」误判为正常。因此改用只有 37 才写入的
 * {@code design_library_item} 里 MODALITY / ASSAY 两个分类。
 *
 * <p><b>状态选择：缺数据返回 {@link Status#UNKNOWN} 而不是 DOWN。</b>
 * 原因：compose 与网关都用 {@code /actuator/health} 判断服务可用性，
 * DOWN 会让 HTTP 返回 503 → docker healthcheck 判死 → core 重启循环 →
 * 网关 depends_on 卡住 → 用户连登录页都打不开，反而看不到任何提示。
 * UNKNOWN 聚合后仍是 HTTP 200，能在 show-details 里暴露明细而不影响启动。
 *
 * <p>bean 名 {@code seedData} 即 health 中的键名，见 application.yml 的
 * {@code management.endpoint.health.show-details: always}。
 *
 * @author SynPharm Team
 */
@Slf4j
@Component("seedData")
@RequiredArgsConstructor
public class SeedDataHealthIndicator implements HealthIndicator {

    /** 低于阈值即判为「种子未落地」，不是「数据偏少」 */
    private static final long MIN_MODALITY = 3;        // 37 实际灌 6
    private static final long MIN_ASSAY = 8;           // 37 实际灌 8
    private static final long MIN_TARGET = 20;         // 49 实际灌 24
    private static final long MIN_LIBRARY_DATASET = 3; // 48 实际灌 3

    private final DesignLibraryItemMapper libraryItemMapper;
    private final TargetMapper targetMapper;
    private final LibraryDatasetMapper libraryDatasetMapper;

    @Override
    public Health health() {
        try {
            long modality = countByCategory("MODALITY");
            long assay = countByCategory("ASSAY");
            long target = targetMapper.selectCount(Wrappers.emptyWrapper());
            long dataset = libraryDatasetMapper.selectCount(Wrappers.emptyWrapper());

            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("modality", modality);
            counts.put("assay", assay);
            counts.put("target", target);
            counts.put("libraryDataset", dataset);

            Map<String, String> missing = new LinkedHashMap<>();
            expect(missing, "modality", modality, MIN_MODALITY);
            expect(missing, "assay", assay, MIN_ASSAY);
            expect(missing, "target", target, MIN_TARGET);
            expect(missing, "libraryDataset", dataset, MIN_LIBRARY_DATASET);

            if (missing.isEmpty()) {
                return Health.up().withDetail("counts", counts).build();
            }
            return Health.status(Status.UNKNOWN)
                    .withDetail("counts", counts)
                    .withDetail("missing", missing)
                    .withDetail("hint", "执行 synpharm-deploy/scripts/apply-seeds.sh 补齐基线数据")
                    .build();
        } catch (Exception e) {
            // DB 不可用等异常按 DOWN 处理：这是真的不可服务，与「数据缺失」不是一回事
            log.warn("seedData 健康检查执行失败", e);
            return Health.down()
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }

    private long countByCategory(String category) {
        return libraryItemMapper.selectCount(
                Wrappers.<DesignLibraryItem>lambdaQuery()
                        .eq(DesignLibraryItem::getCategory, category));
    }

    private void expect(Map<String, String> missing, String key, long actual, long min) {
        if (actual < min) {
            missing.put(key, "actual=" + actual + ", expected>=" + min);
        }
    }
}
