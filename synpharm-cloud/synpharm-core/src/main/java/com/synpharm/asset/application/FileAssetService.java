package com.synpharm.asset.application;

import com.synpharm.asset.api.dto.FileAssetResponse;

/**
 * 文件资产用例（内容寻址 + 引用计数）。
 *
 * <p>本服务只登记**元数据**：大对象一律不落库，DB 里只有 {@code storage_uri}（规范 §3.1）。
 *
 * @author SynPharm Team
 */
public interface FileAssetService {

    /** 取元数据；不存在 → 404 {@code FILE_NOT_FOUND} */
    FileAssetResponse get(Long id);

    /** 引用计数 +1（被引用方在**自己的事务内**调用，规范 §1.5） */
    FileAssetResponse ref(Long id);

    /**
     * 引用计数 -1。
     *
     * <p><b>下限是 0</b>：已经是 0 时不再往下减，只记 WARN —— 重复 unref 属调用方 bug，
     * 但不能因此把计数减成负数（那会让后续的 GC 判定彻底失准）。归零后进入 GC 队列，**不做物理删除**。
     */
    FileAssetResponse unref(Long id);
}
