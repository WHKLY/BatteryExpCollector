package com.example.batteryexpcollector.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class AutomationAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: AutomationAccessibilityService? = null

        fun current(): AutomationAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // no-op
    }

    override fun onInterrupt() {
        // no-op
    }

    fun goHome(logger: AutomationExecutionLogger) {
        logger.info("accessibility_action=home")
        val ok = performGlobalAction(GLOBAL_ACTION_HOME)
        logger.info("accessibility_home_result=$ok")
        Thread.sleep(1200L)
    }

    fun launchHomeIcon(
        label: String,
        expectedPackage: String,
        logger: AutomationExecutionLogger,
        timeoutMs: Long = 10_000L
    ) {
        require(label.isNotBlank()) { "label cannot be blank" }

        goHome(logger)
        val deadline = System.currentTimeMillis() + timeoutMs
        var clicked = false

        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val node = findNodeByLabel(root, label)
                if (node != null) {
                    clicked = clickNodeOrClickableParent(node)
                    logger.info("launcher_click label=$label clicked=$clicked")
                    node.recycle()
                    if (clicked) {
                        Thread.sleep(1500L)
                        logger.info(
                            "launcher_click_completed label=$label expectedPackage=$expectedPackage"
                        )
                        return
                    }
                }
            }
            Thread.sleep(300L)
        }

        throw IllegalStateException("Unable to click launcher icon with label=$label")
    }

    private fun findNodeByLabel(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val textMatches = root.findAccessibilityNodeInfosByText(label)
        textMatches?.firstOrNull()?.let { return AccessibilityNodeInfo.obtain(it) }

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(AccessibilityNodeInfo.obtain(root))

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()

            if (text == label || desc == label || text.contains(label) || desc.contains(label)) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    queue.add(AccessibilityNodeInfo.obtain(child))
                }
            }
            node.recycle()
        }

        return null
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }
}