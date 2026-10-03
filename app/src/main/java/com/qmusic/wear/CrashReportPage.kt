package com.qmusic.wear

import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * 启动崩溃诊断页：纯系统控件实现（Compose 崩溃时也能正常渲染），
 * 支持一键复制日志粘贴反馈；「继续启动」仅用于上次崩溃的瞬时性问题。
 */
internal fun showCrashReportPage(
    activity: ComponentActivity,
    detail: String,
    showContinue: Boolean,
    onContinue: () -> Unit,
) {
    val d = activity.resources.displayMetrics.density
    val pad = (16 * d).toInt()
    val col = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, (24 * d).toInt(), pad, (24 * d).toInt())
    }
    col.addView(TextView(activity).apply {
        text = "启动异常 · 请截图反馈"
        setTextColor(android.graphics.Color.WHITE)
        textSize = 16f
    })
    col.addView(TextView(activity).apply {
        text = "点「复制日志」后粘贴发给开发者"
        setTextColor(android.graphics.Color.LTGRAY)
        textSize = 12f
        setPadding(0, (6 * d).toInt(), 0, (10 * d).toInt())
    })
    col.addView(TextView(activity).apply {
        text = detail
        setTextColor(android.graphics.Color.LTGRAY)
        textSize = 9f
        typeface = android.graphics.Typeface.MONOSPACE
        setTextIsSelectable(true)
    }, LinearLayout.LayoutParams(
        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = (12 * d).toInt() })
    col.addView(Button(activity).apply {
        text = "复制日志"
        setOnClickListener {
            runCatching {
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("qmusic_crash", detail))
                Toast.makeText(
                    activity,
                    "已复制，请粘贴发给开发者",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    })
    if (showContinue) {
        col.addView(Button(activity).apply {
            text = "继续启动"
            setOnClickListener { onContinue() }
        })
    }
    val scroll = ScrollView(activity)
    scroll.addView(col)
    activity.setContentView(scroll)
}