package com.kith.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kith.app.domain.Sticker
import com.kith.app.kithGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class LibraryUiState(
    /** 全部社会（id → 名称），表情包库按社会隔离 */
    val societies: List<Pair<String, String>> = emptyList(),
    val selectedId: String = "",
    val stickers: List<Sticker> = emptyList(),
    /** 审核模型文件状态描述 */
    val guardModelInfo: String = "未导入模型文件",
    val toast: String = "",
    /** 当前选中的下载源（下标指向 ContentGuard.DOWNLOAD_SOURCES） */
    val downloadSourceIdx: Int = 0,
)

/**
 * 资源库管理页的 ViewModel。
 *
 * 「需要添加的库」统一在这里管理：AI 用的表情包库（按社会隔离）、
 * 本地审核的模型文件。别处（聊天表情面板、设置页审核卡片）只放入口
 * 引导到这里，增删改全在这一页，避免管理入口散落各处。
 */
class LibraryViewModel : ViewModel() {

    private val graph = kithGraph

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        reload()
        // 覆盖安装 / 重启后磁盘上的模型文件仍在（filesDir 不随覆盖安装清空），
        // 必须主动读一次，否则页面永远显示初始的「未导入模型文件」。
        refreshGuardModel()
    }

    fun reload() {
        viewModelScope.launch {
            val societies = graph.store.list().map { it.id to it.name }
            val current = _state.value.selectedId
                .takeIf { id -> societies.any { it.first == id } }
                ?: societies.firstOrNull()?.first.orEmpty()
            _state.value = _state.value.copy(societies = societies)
            select(current)
        }
    }

    /** 切换要管理的社会（表情包库按社会隔离）。 */
    fun select(societyId: String) {
        viewModelScope.launch {
            val bundle = graph.store.load(societyId)
            _state.value = _state.value.copy(
                selectedId = societyId,
                stickers = bundle?.stickers.orEmpty(),
            )
        }
    }

    /**
     * 添加一张表情到当前社会的表情包库。
     * 图片落进该社会的 media/ 目录（随社会数据走）。
     */
    fun addSticker(bytes: ByteArray, ext: String, name: String, emotionsRaw: String) {
        val id = _state.value.selectedId
        if (id.isEmpty()) return
        if (bytes.isEmpty()) {
            _state.value = _state.value.copy(toast = "先选一张图片")
            return
        }
        if (name.isBlank()) {
            _state.value = _state.value.copy(toast = "给表情起个名字")
            return
        }
        viewModelScope.launch {
            val f = graph.store.saveMedia(id, bytes, if (ext == "jpeg") "jpg" else ext)
            val lib = graph.store.load(id)?.stickers.orEmpty()
            val emotions = emotionsRaw.split("、", ",", "，", " ")
            val fresh = graph.stickers.add(
                library = lib,
                name = name.trim(),
                imageUrl = f.absolutePath,
                emotions = emotions,
                source = Sticker.SOURCE_USER,
            )
            graph.store.saveStickers(id, fresh)
            _state.value = _state.value.copy(
                stickers = fresh,
                toast = "已添加「${name.trim()}」，AI 现在会用了",
            )
        }
    }

    fun removeSticker(stickerId: String) {
        val id = _state.value.selectedId
        if (id.isEmpty()) return
        viewModelScope.launch {
            val fresh = graph.stickers.remove(_state.value.stickers, stickerId)
            graph.store.saveStickers(id, fresh)
            _state.value = _state.value.copy(stickers = fresh, toast = "已删除")
        }
    }

    fun refreshGuardModel() {
        viewModelScope.launch {
            val f = graph.guard.modelFile()
            _state.value = _state.value.copy(
                guardModelInfo = f?.let {
                    String.format(Locale.US, "已导入 %.0fMB（%s）", it.length() / 1048576.0, it.name)
                } ?: "未导入模型文件",
            )
        }
    }

    // ── 审核模型的在线下载 ──────────────────────────────────────────────────

    private val _download =
        MutableStateFlow<com.kith.app.ai.guard.GuardDownloadState>(
            com.kith.app.ai.guard.GuardDownloadState.Idle,
        )
    val download: StateFlow<com.kith.app.ai.guard.GuardDownloadState> = _download.asStateFlow()

    private var downloadJob: kotlinx.coroutines.Job? = null

    fun selectDownloadSource(idx: Int) {
        _state.value = _state.value.copy(downloadSourceIdx = idx)
    }

    fun startDownload() {
        if (downloadJob?.isActive == true) return
        val url = com.kith.app.ai.guard.ContentGuard.DOWNLOAD_SOURCES
            .getOrNull(_state.value.downloadSourceIdx)?.second ?: return
        downloadJob = viewModelScope.launch {
            graph.guard.downloadModel(url).collect { st ->
                _download.value = st
                when (st) {
                    is com.kith.app.ai.guard.GuardDownloadState.Done -> {
                        refreshGuardModel()
                        _state.value = _state.value.copy(toast = "模型下载完成，可以去设置里开启审核了")
                    }
                    is com.kith.app.ai.guard.GuardDownloadState.Failed ->
                        if (st.message != "已取消") {
                            _state.value = _state.value.copy(toast = "下载失败：${st.message}")
                        }
                    else -> Unit
                }
            }
        }
    }

    fun cancelDownload() {
        graph.guard.cancelDownload()
        downloadJob?.cancel()
        _download.value = com.kith.app.ai.guard.GuardDownloadState.Idle
    }

    /** 导入审核模型：读用户选的文件字节，交给 ContentGuard 落盘与校验。 */
    fun importGuardModel(bytesProvider: suspend () -> ByteArray?) {
        if (_state.value.guardModelInfo.startsWith("导入中")) return
        viewModelScope.launch {
            _state.value = _state.value.copy(guardModelInfo = "导入中…")
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) { bytesProvider() }
                    ?: error("读取不到所选文件")
                graph.guard.importBytes(bytes)
            }.getOrElse { return@launch failure(it.message ?: "导入失败") }
            result.onSuccess { f ->
                _state.value = _state.value.copy(
                    guardModelInfo = String.format(
                        Locale.US, "已导入 %.0fMB（%s）", f.length() / 1048576.0, f.name,
                    ),
                    toast = "审核模型导入成功",
                )
            }.onFailure { failure(it.message ?: "导入失败") }
        }
    }

    private fun failure(msg: String) {
        _state.value = _state.value.copy(
            guardModelInfo = "未导入模型文件",
            toast = "导入失败：$msg",
        )
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = "")
    }

    companion object {
        val Factory = viewModelFactory { initializer { LibraryViewModel() } }
    }
}
