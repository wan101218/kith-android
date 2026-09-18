package com.kith.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kith.app.domain.EndpointKind
import com.kith.app.kithGraph
import com.kith.app.ui.chat.ChatScreen
import com.kith.app.ui.create.CreateSocietyScreen
import com.kith.app.ui.home.HomeScreen
import com.kith.app.ui.log.LogScreen
import com.kith.app.ui.settings.EndpointEditorScreen
import com.kith.app.ui.settings.ModelListScreen
import com.kith.app.ui.settings.ModelPickerScreen
import com.kith.app.ui.settings.SettingsScreen
import com.kith.app.ui.society.SocietyScreen

/**
 * 导航图。
 *
 * 目的地分两类：
 *  - **内容页**：社会列表 → 社会（关系图 / 旁白）→ 与某人的会话 ↘ 日志
 *  - **配置页**：选模型（两步） + 接入点编辑。这三页是「单开一页去配置」的落地 ——
 *    模型列表最多 371 条，需要搜索、按能力筛选、按价格排序，底部面板装不下。
 *
 * 选模型的结果回传不走导航参数，而是走 [kithGraph.pickBus]：
 * 发起方 ViewModel 一直在订阅结果流，拿到后应用到自己的待填槽位。
 * 这样才能在「弹掉两层页面」的同时把结果送到正确的地方。
 */
object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val SETTINGS = "settings"

    /** 资源库：表情包库 / 审核模型文件等「需要添加的库」的统一管理页 */
    const val LIBRARY = "library"

    const val SOCIETY = "society/{societyId}"
    fun society(id: String) = "society/$id"

    const val CHAT = "chat/{societyId}/{charId}"
    fun chat(societyId: String, charId: String) = "chat/$societyId/$charId"

    const val LOGS = "logs/{societyId}"
    fun logs(societyId: String) = "logs/$societyId"

    // ── 配置：模型 ──────────────────────────────────────────────────────────
    /** 第一步：选接入点（用途由 pickBus.request 携带） */
    const val MODEL_PICKER = "model_picker"

    /** 第二步：在该接入点所属厂商下挑具体模型 */
    const val MODEL_LIST = "model_list/{endpointId}"
    fun modelList(endpointId: String) = "model_list/$endpointId"

    /** 新增接入点 */
    const val ENDPOINT_NEW = "endpoint_new/{kind}"
    fun endpointNew(kind: EndpointKind) = "endpoint_new/${kind.name}"

    /** 编辑接入点 */
    const val ENDPOINT_EDIT = "endpoint_edit/{endpointId}"
    fun endpointEdit(endpointId: String) = "endpoint_edit/$endpointId"
}

@Composable
fun KithNavHost() {
    val nav = rememberNavController()
    val graph = kithGraph
    val context = androidx.compose.ui.platform.LocalContext.current

    // ── 本地审核的性能门槛提示 ──
    // 设备不达标时，进应用弹一次窗说明「审核功能已关闭」。
    // 只弹一次（持久化标记），不烦人；设置页里随时能看到完整原因。
    var underSpecOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val cap = com.kith.app.ai.guard.DeviceCapabilityCheck.check(context)
        if (!cap.meetsRequirement && !graph.settings.current.guardUnderSpecNoticeShown) {
            underSpecOpen = true
        }
    }
    if (underSpecOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                underSpecOpen = false
                graph.settings.markGuardUnderSpecNoticeShown()
            },
            title = { Text("本地内容审核已关闭") },
            text = {
                val cap = com.kith.app.ai.guard.DeviceCapabilityCheck.check(context)
                Text(
                    "本机性能未达到本地审核（Qwen3Guard 0.6B）的运行要求。\n\n" +
                        "要求：${com.kith.app.ai.guard.DeviceCapabilityCheck.REQUIREMENT_TEXT}\n" +
                        "本机：${cap.summary()}\n\n" +
                        "已自动关闭审核功能，不影响其它功能的使用。",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    underSpecOpen = false
                    graph.settings.markGuardUnderSpecNoticeShown()
                }) { Text("知道了") }
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavHost(navController = nav, startDestination = Routes.HOME) {

            composable(Routes.HOME) {
                HomeScreen(
                    onCreate = { nav.navigate(Routes.CREATE) },
                    onOpen = { nav.navigate(Routes.society(it)) },
                )
            }

            composable(Routes.CREATE) {
                CreateSocietyScreen(
                    onNavigateToPicker = { nav.navigate(Routes.MODEL_PICKER) },
                    onCreated = { id ->
                        // 创建完直接进入新社会，并把它上面的创建页弹掉
                        nav.navigate(Routes.society(id)) {
                            popUpTo(Routes.CREATE) { inclusive = true }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(
                Routes.SOCIETY,
                arguments = listOf(navArgument("societyId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("societyId").orEmpty()
                SocietyScreen(
                    societyId = id,
                    onNavigateToPicker = { nav.navigate(Routes.MODEL_PICKER) },
                    onBack = { nav.popBackStack() },
                    onOpenChat = { charId -> nav.navigate(Routes.chat(id, charId)) },
                    onOpenLogs = { nav.navigate(Routes.logs(id)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }

            composable(
                Routes.CHAT,
                arguments = listOf(
                    navArgument("societyId") { type = NavType.StringType },
                    navArgument("charId") { type = NavType.StringType },
                ),
            ) { entry ->
                ChatScreen(
                    societyId = entry.arguments?.getString("societyId").orEmpty(),
                    charId = entry.arguments?.getString("charId").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onNavigateToLibrary = { nav.navigate(Routes.LIBRARY) },
                )
            }

            composable(
                Routes.LOGS,
                arguments = listOf(navArgument("societyId") { type = NavType.StringType }),
            ) { entry ->
                LogScreen(
                    societyId = entry.arguments?.getString("societyId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onNewEndpoint = { kind -> nav.navigate(Routes.endpointNew(kind)) },
                    onEditEndpoint = { ep -> nav.navigate(Routes.endpointEdit(ep.id)) },
                    onNavigateToLibrary = { nav.navigate(Routes.LIBRARY) },
                )
            }

            composable(Routes.LIBRARY) {
                com.kith.app.ui.library.LibraryScreen(
                    onBack = { nav.popBackStack() },
                )
            }

            // ── 配置页：选模型 ──────────────────────────────────────────────

            composable(Routes.MODEL_PICKER) {
                // 用途由 pickBus 携带，每次进入读取当前请求
                val request = graph.pickBus.request
                ModelPickerScreen(
                    request = request,
                    onPickEndpoint = { ep -> nav.navigate(Routes.modelList(ep.id)) },
                    onPickedSaved = { ref ->
                        graph.pickBus.post(ref)
                        nav.popBackStack()
                    },
                    onManageEndpoints = {
                        nav.navigate(Routes.endpointNew(request.kind))
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(
                Routes.MODEL_LIST,
                arguments = listOf(navArgument("endpointId") { type = NavType.StringType }),
            ) { entry ->
                val endpointId = entry.arguments?.getString("endpointId").orEmpty()
                // 接入点从设置里现查，保证拿到的是最新数据
                val endpoint = graph.settings.current.endpoint(endpointId)

                if (endpoint == null) {
                    // 接入点在别处被删掉了，直接退回上一页而不是显示一个空页
                    LaunchedEffect(endpointId) { nav.popBackStack() }
                } else {
                    ModelListScreen(
                        endpoint = endpoint,
                        onPicked = { ref ->
                            graph.pickBus.post(ref)
                            // 一次弹掉「模型列表 + 选择页」两层，直接回到发起方
                            nav.popBackStack(Routes.MODEL_PICKER, inclusive = true)
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
            }

            // ── 配置页：接入点 ──────────────────────────────────────────────

            composable(
                Routes.ENDPOINT_NEW,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
            ) { entry ->
                val kind = EndpointKind.of(entry.arguments?.getString("kind"))
                EndpointEditorScreen(
                    kind = kind,
                    existing = null,
                    onSaved = { ep ->
                        graph.settings.upsertEndpoint(ep)
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable(
                Routes.ENDPOINT_EDIT,
                arguments = listOf(navArgument("endpointId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("endpointId").orEmpty()
                val existing = graph.settings.current.endpoint(id)
                EndpointEditorScreen(
                    kind = existing?.kind ?: EndpointKind.LLM,
                    existing = existing,
                    onSaved = { ep ->
                        graph.settings.upsertEndpoint(ep)
                        nav.popBackStack()
                    },
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
