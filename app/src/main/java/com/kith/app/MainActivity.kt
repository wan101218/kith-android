package com.kith.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kith.app.ui.nav.KithNavHost
import com.kith.app.ui.theme.KithTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏 / 导航栏透明：顶栏自己做毛玻璃，内容可以滚到状态栏底下
        enableEdgeToEdge()
        setContent {
            // 订阅设置流：设置页里切「浅色 / 深色 / 跟随系统」要立即生效，
            // 而不是读一次快照后要重启应用才变
            val settings by kithGraph.settings.state.collectAsState()
            KithTheme(
                darkTheme = when (settings.themeMode) {
                    com.kith.app.data.settings.ThemeMode.SYSTEM ->
                        androidx.compose.foundation.isSystemInDarkTheme()
                    com.kith.app.data.settings.ThemeMode.LIGHT -> false
                    com.kith.app.data.settings.ThemeMode.DARK -> true
                },
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    KithNavHost()
                }
            }
        }
    }
}
