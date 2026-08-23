package com.nswsys.nuviobridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MatchChooserActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val ids = intent.getIntArrayExtra(EXTRA_IDS) ?: intArrayOf()
        val types = intent.getStringArrayExtra(EXTRA_TYPES).orEmpty()
        val titles = intent.getStringArrayExtra(EXTRA_TITLES).orEmpty()
        val years = intent.getIntArrayExtra(EXTRA_YEARS) ?: intArrayOf()
        if (ids.isEmpty() || ids.size != types.size || ids.size != titles.size) {
            finish()
            return
        }

        val spanish = Locale.getDefault().language.equals("es", ignoreCase = true)
        val metrics = resources.displayMetrics
        val uiScale = minOf(metrics.widthPixels / 1920f, metrics.heightPixels / 1080f)
            .coerceAtLeast(0.65f)
        fun px(value: Int) = (value * uiScale).toInt()

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            dimAmount = 0.34f
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = px(58)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(30), px(20), px(30), px(22))
            background = roundedBackground(Color.rgb(28, 30, 35), 24f, uiScale)
            elevation = px(20).toFloat()
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(TextView(this).apply {
            text = "NUVIO"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(15).toFloat())
            setTextColor(Color.rgb(188, 158, 255))
            letterSpacing = 0.12f
        })
        heading.addView(TextView(this).apply {
            text = if (spanish) {
                "  Elige la versión de “${TitleNormalizer.withoutYear(query)}”"
            } else {
                "  Choose the version of “${TitleNormalizer.withoutYear(query)}”"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(23).toFloat())
            setTextColor(Color.WHITE)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val cancel = TextView(this).apply {
            text = if (spanish) "Cancelar" else "Cancel"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(17).toFloat())
            gravity = Gravity.CENTER
            setTextColor(focusTextColors(Color.rgb(22, 22, 25), Color.rgb(225, 225, 232)))
            isFocusable = true
            isClickable = true
            background = optionBackground(uiScale, compact = true)
            setOnClickListener { finish() }
        }
        heading.addView(cancel, LinearLayout.LayoutParams(px(150), px(46)).apply {
            marginStart = px(20)
        })
        content.addView(heading, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val optionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var firstOption: View? = null
        ids.indices.forEach { index ->
            val type = runCatching { MediaType.valueOf(types[index]) }.getOrNull()
                ?: return@forEach
            val year = years.getOrNull(index)?.takeIf { it > 0 }
            val match = MediaMatch(ids[index], type, titles[index], 100.0, year)
            val option = createOption(match, spanish, uiScale).apply {
                setOnClickListener {
                    if (query.isNotBlank()) MatchCache(this@MatchChooserActivity).put(query, match)
                    NuvioLauncher.open(this@MatchChooserActivity, match)
                    finish()
                }
            }
            if (firstOption == null) firstOption = option
            optionRow.addView(option, LinearLayout.LayoutParams(px(245), px(92)).apply {
                marginEnd = px(12)
            })
        }

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(optionRow, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        content.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            px(100)
        ).apply { topMargin = px(14) })

        setContentView(content)
        val trayWidth = minOf(px(1480), metrics.widthPixels - px(128))
        window.setLayout(trayWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        firstOption?.requestFocus()
    }

    private fun createOption(match: MediaMatch, spanish: Boolean, uiScale: Float): View {
        fun px(value: Int) = (value * uiScale).toInt()
        val typeLabel = when {
            spanish && match.type == MediaType.MOVIE -> "PELÍCULA"
            spanish -> "SERIE"
            match.type == MediaType.MOVIE -> "MOVIE"
            else -> "SERIES"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22), 0, px(18), 0)
            isFocusable = true
            isClickable = true
            background = optionBackground(uiScale)
            elevation = px(2).toFloat()
            addView(TextView(this@MatchChooserActivity).apply {
                text = match.year?.toString() ?: if (spanish) "Sin año" else "No year"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(29).toFloat())
                maxLines = 1
                setTextColor(focusTextColors(Color.rgb(22, 22, 25), Color.WHITE))
                isDuplicateParentStateEnabled = true
            })
            addView(TextView(this@MatchChooserActivity).apply {
                text = typeLabel
                setTextSize(TypedValue.COMPLEX_UNIT_PX, px(16).toFloat())
                letterSpacing = 0.08f
                setTextColor(focusTextColors(Color.rgb(70, 70, 76), Color.rgb(187, 188, 197)))
                isDuplicateParentStateEnabled = true
                setPadding(0, px(3), 0, 0)
            })
        }
    }

    private fun optionBackground(uiScale: Float, compact: Boolean = false): StateListDrawable {
        fun shape(color: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = (if (compact) 25f else 18f) * uiScale
            if (stroke != Color.TRANSPARENT) {
                setStroke((1.4f * uiScale).toInt().coerceAtLeast(1), stroke)
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(232, 232, 238), Color.TRANSPARENT))
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(242, 242, 238), Color.WHITE))
            addState(intArrayOf(), shape(Color.rgb(50, 52, 59), Color.rgb(91, 94, 105)))
        }
    }

    private fun roundedBackground(color: Int, radiusDp: Float, density: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.rgb(71, 73, 82))
        }

    private fun focusTextColors(focused: Int, normal: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
        intArrayOf(focused, normal)
    )

    companion object {
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_TYPES = "types"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_YEARS = "years"

        fun intent(context: Context, resolution: MediaResolution): Intent {
            val options = resolution.options
            return Intent(context, MatchChooserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_QUERY, resolution.query)
                putExtra(EXTRA_IDS, options.map(MediaMatch::tmdbId).toIntArray())
                putExtra(EXTRA_TYPES, options.map { it.type.name }.toTypedArray())
                putExtra(EXTRA_TITLES, options.map(MediaMatch::title).toTypedArray())
                putExtra(EXTRA_YEARS, options.map { it.year ?: -1 }.toIntArray())
            }
        }
    }
}
