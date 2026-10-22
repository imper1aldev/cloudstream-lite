package com.lagradost.cloudstream3

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

/** Small native accessibility helpers shared by Details and navigation checks. */
internal object LiteUiAutomation {
    fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if(root==null)return emptyList()
        return listOf(root)+(0 until root.childCount).flatMap { nodes(root.getChild(it)) }
    }
    fun clickLabel(label: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val deadline=System.currentTimeMillis()+3_000
        var tree=emptyList<AccessibilityNodeInfo>()
        var clickable: AccessibilityNodeInfo?=null
        while(clickable==null && System.currentTimeMillis()<deadline) {
            if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
            tree=nodes(instrumentation.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }
            val candidates=tree.filter { it.contentDescription?.toString()?.let { value -> value.equals(label,true) || value.startsWith("$label ·",true) }==true } +
                tree.filter { it.text?.toString()?.equals(label,true)==true }
            clickable=candidates.firstNotNullOfOrNull { node ->
                var current: AccessibilityNodeInfo?=node
                while(current!=null && !current.isClickable)current=current.parent
                current
            }
            if(clickable==null)Thread.sleep(100)
        }
        checkNotNull(clickable) { "No clickable action: $label; visible="+tree.mapNotNull { it.contentDescription?.toString() ?: it.text?.toString() } }
        assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
    }
    fun waitFor(predicate:()->Boolean) {
        val deadline=System.currentTimeMillis()+3_000
        while(!predicate() && System.currentTimeMillis()<deadline)Thread.sleep(50)
        assertTrue("Action did not update its real state",predicate())
    }
}
