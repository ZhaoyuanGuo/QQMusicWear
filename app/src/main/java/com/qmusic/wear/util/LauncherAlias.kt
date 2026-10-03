package com.qmusic.wear.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 按当前音乐源切换桌面入口（名称 + 图标）。
 *
 * 每个源对应一个已声明的 activity-alias（见 AndroidManifest.xml），
 * 这里只启用当前源的那个、禁用其余：桌面显示的名称/图标随源变化。
 * 用 SharedPreferences 记录上次应用值，避免每次冷启动都触发桌面刷新。
 */
object LauncherAlias {

    private const val TAG = "LauncherAlias"
    private const val PREFS = "qmusic_source"
    private const val KEY_LAST = "launcher_alias_source"

    private val ALIASES = mapOf(
        "qmusic-web" to "com.qmusic.wear.MainActivityQq",
        "kugou-web" to "com.qmusic.wear.MainActivityKugou",
        "netease-web" to "com.qmusic.wear.MainActivityNetease",
        "fanqie-web" to "com.qmusic.wear.MainActivityFanqie",
    )

    /** 应用当前源对应的桌面入口；与上次一致则跳过 */
    fun apply(context: Context, sourceId: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_LAST, null) == sourceId) return
            val target = ALIASES[sourceId] ?: ALIASES.getValue("qmusic-web")
            val pm = context.packageManager
            // 先启用目标，再禁用其它，避免当前图标短暂消失
            setState(pm, context, target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            ALIASES.values.filter { it != target }.forEach { alias ->
                setState(pm, context, alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            }
            prefs.edit().putString(KEY_LAST, sourceId).apply()
            Log.d(TAG, "桌面入口已切换到 $sourceId")
        }.onFailure { Log.w(TAG, "切换桌面入口失败: ${it.message}") }
    }

    private fun setState(pm: PackageManager, context: Context, alias: String, state: Int) {
        runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, alias),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.w(TAG, "setComponentEnabledSetting($alias) 失败: ${it.message}") }
    }
}