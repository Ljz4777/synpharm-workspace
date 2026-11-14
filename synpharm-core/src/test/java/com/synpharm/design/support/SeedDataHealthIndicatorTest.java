package com.synpharm.design.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.synpharm.design.infrastructure.persistence.DesignLibraryItemMapper;
import com.synpharm.design.infrastructure.persistence.LibraryDatasetMapper;
import com.synpharm.design.infrastructure.persistence.TargetMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * 基线种子数据健康指标。
 *
 * <p>这条探针的价值在于「缺数据」必须能被运维直接看到，而不是等用户来报
 * 「新建项目点不动」。所以三个分支（UP / UNKNOWN / DOWN）都要钉住。
 *
 * @author SynPharm Team
 */
class SeedDataHealthIndicatorTest {

    private final DesignLibraryItemMapper itemMapper = mock(DesignLibraryItemMapper.class);
    private final TargetMapper targetMapper = mock(TargetMapper.class);
    private final LibraryDatasetMapper datasetMapper = mock(LibraryDatasetMapper.class);

    private SeedDataHealthIndicator newIndicator() {
        return new SeedDataHealthIndicator(itemMapper, targetMapper, datasetMapper);
    }

    /**
     * MODALITY 与 ASSAY 都来自 itemMapper，health() 里按「MODALITY → ASSAY」顺序各查一次，
     * 因此用连续返回值模拟；Mockito 在后继调用上会重复最后一个值。
     */
    private void stub(long modality, long assay, long target, long dataset) {
        when(itemMapper.selectCount(any())).thenReturn(modality, assay);
        when(targetMapper.selectCount(any())).thenReturn(target);
        when(datasetMapper.selectCount(any())).thenReturn(dataset);
    }

    @Test
    @DisplayName("字典为空时返回 UNKNOWN 并列出缺失项")
    void unknownWhenSeedsMissing() {
        stub(0, 0, 0, 3);
        Health health = newIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails().get("missing")).isNotNull();
        assertThat(health.getDetails().get("missing").toString())
                .contains("modality", "assay", "target")
                .doesNotContain("libraryDataset");
        assertThat(health.getDetails().get("hint")).asString().contains("apply-seeds.sh");
    }

    @Test
    @DisplayName("数据齐备时返回 UP")
    void upWhenSeedsPresent() {
        stub(6, 8, 24, 3);
        Health health = newIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("missing")).isNull();
        assertThat(health.getDetails().get("counts").toString())
                .contains("modality=6", "assay=8", "target=24", "libraryDataset=3");
    }

    @Test
    @DisplayName("查询抛异常时返回 DOWN")
    void downWhenQueryFails() {
        when(itemMapper.selectCount(any())).thenThrow(new RuntimeException("db down"));
        Health health = newIndicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error")).asString().contains("db down");
    }
}
