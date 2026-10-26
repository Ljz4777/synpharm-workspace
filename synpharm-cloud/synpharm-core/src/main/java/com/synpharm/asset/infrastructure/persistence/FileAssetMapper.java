package com.synpharm.asset.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.synpharm.asset.domain.model.FileAsset;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件资产 Mapper。
 *
 * @author SynPharm Team
 */
@Mapper
public interface FileAssetMapper extends BaseMapper<FileAsset> {
}
