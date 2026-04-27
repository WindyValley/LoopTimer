package com.looptimer

/**
 * 版本号管理
 * 格式: MAJOR.MINOR.PATCH
 *
 * 版本规则:
 * - MAJOR: 主版本号，大幅更新时变更
 * - MINOR: 次版本号，新增功能时变更
 * - PATCH: 补丁版本号，每次构建前递增
 *
 * 注意: 此文件版本号需与 gradle.properties 保持同步
 *       VERSION_NAME=1.0.1 对应 MAJOR=1, MINOR=0, PATCH=1
 */
object VERSION {
    const val MAJOR = 1
    const val MINOR = 0
    const val PATCH = 1  // 1.0.1，与 gradle.properties VERSION_PATCH 同步

    const val NAME = "$MAJOR.$MINOR.$PATCH"
}
