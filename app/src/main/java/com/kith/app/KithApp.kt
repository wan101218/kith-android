package com.kith.app

import android.app.Application

/**
 * 应用入口。
 *
 * 持有全局单例容器 [AppGraph]。之所以手写而不用 Hilt/Koin：
 * 本项目的依赖关系是明确且扁平的（设置 → 目录 → 仓储 → 引擎），
 * 手写容器更透明，也避免引入注解处理器拖慢构建。
 */
class KithApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = AppGraph(this)
    }

    companion object {
        lateinit var instance: KithApp
            private set
    }
}
