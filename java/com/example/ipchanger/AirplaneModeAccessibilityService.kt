package com.example.ipchanger

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class AirplaneModeAccessibilityService : AccessibilityService() {

    private val TAG = "IPChangerService"
    private var isWorking = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.android.settings") return

        // Check if we requested the toggle
        val prefs = getSharedPreferences("com.example.ipchanger.PREFS", Context.MODE_PRIVATE)
        val doToggle = prefs.getBoolean("DO_TOGGLE", false)

        if (!doToggle) return
        if (isWorking) return

        Log.d(TAG, "Event detected in Settings. Starting toggle sequence.")
        
        val rootNode = rootInActiveWindow ?: return
        
        // Find Airplane Mode switch
        val airplaneNodes = rootNode.findAccessibilityNodeInfosByText("Airplane mode")
        if (airplaneNodes.isEmpty()) {
             // Try just "Airplane"
             val partialNodes = rootNode.findAccessibilityNodeInfosByText("Airplane")
             if (partialNodes.isNotEmpty()) {
                 attemptToggle(partialNodes[0], prefs)
             }
             return
        }
        
        attemptToggle(airplaneNodes[0], prefs)
    }

    private fun attemptToggle(node: AccessibilityNodeInfo, prefs: SharedPreferences) {
        // The node might be the textview. We need to find the switch.
        // Usually it's a parent's child or the node itself is clickable.
        
        var clickableNode: AccessibilityNodeInfo? = null
        
        // Case 1: The node itself is checkable/clickable
        if (node.isCheckable || node.isClickable) {
            clickableNode = node
        } else {
            // Case 2: Parent is clickable (common in settings list)
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    clickableNode = parent
                    break
                }
                parent = parent.parent
            }
        }

        if (clickableNode != null) {
            isWorking = true
            Log.d(TAG, "Found clickable node. Toggling ON.")
            clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            
            // Wait 5 seconds then Toggle OFF
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Waited 5s. Searching for node again to Toggle OFF.")
                // Must find node again as the UI might have refreshed
                val currentRoot = rootInActiveWindow
                if (currentRoot != null) {
                     val newNodes = currentRoot.findAccessibilityNodeInfosByText("Airplane")
                     if (newNodes.isNotEmpty()) {
                         val newNode = newNodes[0]
                         // Find clickable parent again
                         var targetNode = newNode
                         if (!targetNode.isClickable) {
                             var p = targetNode.parent
                             while (p != null) {
                                 if (p.isClickable) {
                                     targetNode = p
                                     break
                                 }
                                 p = p.parent
                             }
                         }
                         
                         Log.d(TAG, "Toggling OFF.")
                         targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                     }
                }
                
                // Done. Reset flag and Back.
                prefs.edit().putBoolean("DO_TOGGLE", false).apply()
                isWorking = false
                
                // Wait small bit for toggle animation then back
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "Going Back.")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 500)
                
            }, 5000)
        }
    }

    override fun onInterrupt() {
        isWorking = false
    }
}
