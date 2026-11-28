package com.noyclic.xrclientprobe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ProbeActivity : Activity() {
    internal var serviceReportText: String = ""
        private set

    internal var loaderReportText: String = ""
        private set

    internal var combinedReportText: String = ""
        private set

    internal var assessmentJsonText: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serviceReportText = ExternalRuntimeClientProbe.inspect(this).toDisplayText()
        loaderReportText = OpenXrLoaderProbe.inspect(this)
        val assessment = ExternalOpenXrCompatibilityAssessment.analyze(
            serviceReport = serviceReportText,
            loaderReport = loaderReportText
        )
        assessmentJsonText = assessment.toJson()
        combinedReportText = buildString {
            appendLine("=== External OpenXR compatibility assessment ===")
            appendLine(assessment.toDisplayText())
            appendLine()
            appendLine("=== Android service and RuntimeBroker visibility ===")
            appendLine(serviceReportText)
            appendLine()
            appendLine("=== Official Khronos OpenXR loader ===")
            append(loaderReportText)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 17, 23))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
        }
        actions.addView(Button(this).apply {
            text = "COPY REPORT"
            setOnClickListener { copyTextReport() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "SHARE JSON"
            setOnClickListener { shareJsonReport() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val body = TextView(this).apply {
            text = combinedReportText
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(14, 17, 23))
            setPadding(24, 16, 24, 24)
            gravity = Gravity.TOP
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(body)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)
    }

    private fun copyTextReport() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EndXR OpenXR diagnostic report", combinedReportText))
        Toast.makeText(this, "OpenXR report copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareJsonReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "EndXR external OpenXR compatibility assessment")
            putExtra(Intent.EXTRA_TEXT, assessmentJsonText)
        }
        startActivity(Intent.createChooser(send, "Share OpenXR assessment"))
    }
}
