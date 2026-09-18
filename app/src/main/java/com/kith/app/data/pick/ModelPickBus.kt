package com.kith.app.data.pick

import com.kith.app.domain.EndpointKind
import com.kith.app.domain.ModelRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 发起一次「选模型」时的上下文，用于让选择页知道这次是在给谁选。 */
data class PickRequest(
    val kind: EndpointKind = EndpointKind.LLM,
    /** 例如「旁白使用的模型」「「小明」使用哪个模型」 */
    val title: String = "选择模型",
    val subtitle: String = "",
)

/**
 * 「选模型」的结果总线。
 *
 * 为什么用总线而不是导航参数回传：
 * 选择模型被三个地方用到（创建社会的三个槽位、人物卡、社会模型面板），
 * 它们的状态分别存在于各自的 ViewModel 里。而 Android 的 Navigation 组件对
 * 「返回结果」的支持要求可序列化参数，ModelRef 要来回转 JSON 才能传，
 * 且调用方还得从 navController 的回调里取 —— 链路又长又脆。
 *
 * 这里改成：发起方先 [start] 登记意图 → 跳转到选择页 → 选择页 [post] 结果并返回 →
 * 发起方的 ViewModel 一直在订阅 [result]，拿到后应用到自己的 pending 槽位并 [consume]。
 * ViewModel 在导航来回中不会被销毁，所以整条链路是可靠的。
 */
class ModelPickBus {

    var request: PickRequest = PickRequest()
        private set

    private val _result = MutableStateFlow<ModelRef?>(null)
    val result: StateFlow<ModelRef?> = _result.asStateFlow()

    /** 跳转前调用，登记这次选择的用途，并清掉上一次的残留结果。 */
    fun start(kind: EndpointKind, title: String, subtitle: String = "") {
        request = PickRequest(kind, title, subtitle)
        _result.value = null
    }

    fun post(ref: ModelRef) {
        _result.value = ref
    }

    fun consume() {
        _result.value = null
    }
}
