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
 * 当 MINOR 或 MAJOR 变化时，PATCH 会归零
 */
object VERSION {
    const val MAJOR = 1
    const val MINOR = 0
    const val PATCH = 1  // 1.0.0 → 1.0.1，下次构建前手动递增
    
    const val NAME = "$MAJOR.$MINOR.$PATCH"
}
