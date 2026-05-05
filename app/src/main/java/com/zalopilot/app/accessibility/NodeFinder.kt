package com.zalopilot.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.data.model.SelectorConfig
import com.zalopilot.app.data.model.SelectorItem
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NodeFinder @Inject constructor(
    private val selectorConfig: SelectorConfig
) {
    fun findLikeButtons(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        root ?: return emptyList()
        val selector = selectorConfig.getLikeButton()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, selector, nodes)
        return nodes
    }

    fun findTimelineTab(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        return findNode(root, selectorConfig.getTabTimeline())
    }

    fun shouldLike(node: AccessibilityNodeInfo): Boolean {
        val selector = selectorConfig.getLikeButton()
        if (node.isChecked) return false
        val text = node.text?.toString() ?: return false
        if (selector.alreadySelectedText != null && text == selector.alreadySelectedText) return false
        return text == selector.textFallback || text.contains("Thích")
    }

    private fun findNode(root: AccessibilityNodeInfo, selector: SelectorItem): AccessibilityNodeInfo? {
        if (selector.resourceId.isNotEmpty()) {
            root.findAccessibilityNodeInfosByViewId(selector.resourceId)
                .firstOrNull()?.let { return it }
        }
        if (selector.textFallback != null) {
            root.findAccessibilityNodeInfosByText(selector.textFallback)
                .firstOrNull()?.let { return it }
        }
        if (selector.contentDescFallback != null) {
            findByContentDesc(root, selector.contentDescFallback)?.let { return it }
        }
        return null
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        selector: SelectorItem,
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (selector.resourceId.isNotEmpty()) {
            val byId = node.findAccessibilityNodeInfosByViewId(selector.resourceId)
            result.addAll(byId)
            if (byId.isNotEmpty()) return
        }
        if (selector.textFallback != null) {
            val byText = node.findAccessibilityNodeInfosByText(selector.textFallback)
            result.addAll(byText)
        }
    }

    private fun findByContentDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString() == desc) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findByContentDesc(child, desc)
            if (found != null) return found
        }
        return null
    }
}
