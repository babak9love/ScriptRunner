package com.scriptrunner.app

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

private fun blend(a: Int, b: Int, r: Float): Int = Color.rgb(
    (Color.red(a) * (1 - r) + Color.red(b) * r).toInt(),
    (Color.green(a) * (1 - r) + Color.green(b) * r).toInt(),
    (Color.blue(a) * (1 - r) + Color.blue(b) * r).toInt()
)

private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

/** Colours derived from the current script's background so the sheets match its theme. */
class SheetColors(val bg: Int) {
    val light = isLightColor(bg)
    val text = if (light) 0xFF1F2933.toInt() else 0xFFE8EDF2.toInt()
    val sub = if (light) 0xFF5B6673.toInt() else 0xFF8B98A7.toInt()
    val surface = blend(bg, if (light) Color.BLACK else Color.WHITE, if (light) 0.05f else 0.07f)
    val accent = if (light) 0xFF0D9488.toInt() else 0xFF2DD4BF.toInt()
    val onAccent = 0xFF06201C.toInt()
    val danger = if (light) 0xFFDC2626.toInt() else 0xFFF87171.toInt()
    val ok = 0xFF22C55E.toInt()
}

/** A rounded bottom sheet with a grab handle, built from plain framework views. */
class Sheet(private val act: Activity, val c: SheetColors) {
    private val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    val root = LinearLayout(act)

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    init {
        root.orientation = LinearLayout.VERTICAL
        val r = dp(26).toFloat()
        root.background = GradientDrawable().apply {
            setColor(c.bg)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        root.setPadding(dp(16), dp(10), dp(16), dp(18))

        val handle = View(act)
        handle.background = GradientDrawable().apply {
            setColor(withAlpha(c.text, 0x40))
            cornerRadius = dp(2).toFloat()
        }
        root.addView(
            handle,
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        )

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.55f)
        }
    }

    fun dismiss() = dialog.dismiss()

    fun show() {
        dialog.show()
        root.post {
            root.translationY = root.height.toFloat()
            root.animate().translationY(0f).setDuration(220).start()
        }
    }

    fun ripple(fill: Int, radius: Float, stroke: Int? = null): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(2), stroke)
        }
        return RippleDrawable(ColorStateList.valueOf(withAlpha(c.text, 0x22)), shape, null)
    }

    /** Title, plus an optional status line with a coloured dot. */
    fun title(title: String, sub: String?, dot: Int?) {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(14))
        }
        col.addView(TextView(act).apply {
            text = title
            setTextColor(c.text)
            textSize = 21f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        if (sub != null) {
            val line = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, 0)
            }
            if (dot != null) {
                val d = View(act)
                d.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dot)
                }
                line.addView(d, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            }
            line.addView(TextView(act).apply {
                text = sub
                setTextColor(c.sub)
                textSize = 13f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(line)
        }
        root.addView(col)
    }

    /** An action row: round icon badge, title, optional subtitle. */
    fun row(icon: Int, title: String, sub: String?, tint: Int = c.accent, onClick: () -> Unit) {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(16), dp(12))
            background = ripple(c.surface, dp(18).toFloat())
            isClickable = true
            setOnClickListener {
                dismiss()
                onClick()
            }
        }
        val ic = ImageView(act).apply {
            setImageResource(icon)
            setColorFilter(tint)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(tint, 0x26))
            }
        }
        row.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))

        val tx = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        tx.addView(TextView(act).apply {
            text = title
            setTextColor(if (tint == c.danger) c.danger else c.text)
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        if (sub != null) {
            tx.addView(TextView(act).apply {
                text = sub
                setTextColor(c.sub)
                textSize = 13f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    /** Selectable script cards + "always run" switch + Run button. */
    fun picker(
        scripts: List<Script>,
        currentPath: String?,
        defPath: String?,
        onRun: (Script, Boolean) -> Unit
    ) {
        var sel = scripts.indexOfFirst { it.path == currentPath }.coerceAtLeast(0)
        val cards = ArrayList<LinearLayout>()

        fun style(i: Int) {
            val on = i == sel
            cards[i].background = ripple(
                if (on) blend(c.surface, c.accent, 0.16f) else c.surface,
                dp(18).toFloat(),
                if (on) c.accent else c.surface
            )
        }

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        scripts.forEachIndexed { i, s ->
            val card = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
            }
            val tx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(TextView(act).apply {
                text = s.name
                setTextColor(c.text)
                textSize = 16f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.END
            })
            tx.addView(TextView(act).apply {
                text = "${s.path}  ·  port ${s.port}"
                setTextColor(c.sub)
                textSize = 12f
                setSingleLine()
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            card.addView(tx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (s.path == defPath) {
                card.addView(ImageView(act).apply {
                    setImageResource(R.drawable.ic_star)
                    setColorFilter(c.accent)
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(10) })
            }
            cards.add(card)
            card.setOnClickListener {
                val old = sel
                sel = i
                style(old)
                style(i)
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        for (i in scripts.indices) style(i)

        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        // "Always run this one"
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val sw = Switch(act).apply {
            thumbTintList = ColorStateList(states, intArrayOf(c.accent, blend(c.bg, c.sub, 0.8f)))
            trackTintList = ColorStateList(states, intArrayOf(withAlpha(c.accent, 0x66), withAlpha(c.text, 0x33)))
        }
        val remember = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = ripple(c.surface, dp(18).toFloat())
            isClickable = true
            setOnClickListener { sw.toggle() }
        }
        val rtx = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        rtx.addView(TextView(act).apply {
            text = act.getString(R.string.always_run_title)
            setTextColor(c.text)
            textSize = 15f
        })
        rtx.addView(TextView(act).apply {
            text = act.getString(R.string.always_run_sub)
            setTextColor(c.sub)
            textSize = 12f
        })
        remember.addView(rtx, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        remember.addView(sw)
        root.addView(remember, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(12) })

        // Run button
        val run = TextView(act).apply {
            text = act.getString(R.string.run)
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(c.onAccent)
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(Color.BLACK, 0x33)),
                GradientDrawable().apply {
                    setColor(c.accent)
                    cornerRadius = dp(28).toFloat()
                },
                null
            )
            setOnClickListener {
                dismiss()
                onRun(scripts[sel], sw.isChecked)
            }
        }
        root.addView(run, LinearLayout.LayoutParams(-1, -2))
    }
}
