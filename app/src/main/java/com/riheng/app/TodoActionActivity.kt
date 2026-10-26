package com.riheng.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** 小组件列表点击的中转站（不显示界面）：完成待办，或带着分组信息打开应用。 */
class TodoActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(TodoWidgetProvider.EXTRA_ID) ?: ""
        when (intent.getStringExtra(TodoWidgetProvider.EXTRA_ACTION)) {
            "done" -> if (id.isNotEmpty()) TodoWidgetProvider.completeAndRefresh(this, id)
            "undo" -> if (id.isNotEmpty()) TodoWidgetProvider.undoAndRefresh(this, id)
            "open" -> startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_PAGE, "todo")
                    .putExtra(MainActivity.EXTRA_OPEN_VIEW, intent.getStringExtra(TodoWidgetProvider.EXTRA_VIEW) ?: "")
                    .putExtra(MainActivity.EXTRA_OPEN_ID, id)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
