package com.anezium.rokidnexus.sample

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.HubTarget

class HelloActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padding, padding, padding, padding)
                setBackgroundColor(Color.rgb(16, 20, 17))
                addView(TextView(this@HelloActivity).apply {
                    text = "Hello Nexus plugin"
                    textSize = 26f
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@HelloActivity).apply {
                    text = "Install this APK, then approve its Surfaces access in Rokid Nexus. Open it from the glasses launcher to see the sample card."
                    textSize = 16f
                    setTextColor(Color.LTGRAY)
                    setPadding(0, padding / 2, 0, padding)
                })
                addView(Button(this@HelloActivity).apply {
                    text = "Open Nexus plugin access"
                    setOnClickListener { openPluginAccess() }
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
    }

    private fun openPluginAccess() {
        val intent = Intent().setComponent(
            ComponentName(
                HubTarget.PHONE.packageName,
                "com.anezium.rokidbus.phone.PluginPermissionsActivity",
            ),
        )
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "Install Rokid Nexus first", Toast.LENGTH_SHORT).show()
        }
    }
}
