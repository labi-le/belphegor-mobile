package belphegor.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Apple-HIG-styled primitives (DESIGN.md §4) shared by [MainActivity]'s
 * sections: inset-grouped cards, hairline dividers, iOS-tinted switches,
 * borderless field rows, and grouped-list button rows. Kept as Context /
 * LinearLayout extensions so the Activity stays focused on wiring and state.
 */

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.color(res: Int): Int = ContextCompat.getColor(this, res)

fun fullWidth(top: Int = 0) =
    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top }

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(child)
}

fun Context.resolveColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

/**
 * Inset group (iOS Settings card): 10dp continuous-ish corners, flat (depth
 * comes from the background ramp, not shadows), zero content padding — rows
 * own their 16dp/11dp padding so hairline dividers can run edge-to-edge.
 */
fun Context.card(build: LinearLayout.() -> Unit): MaterialCardView {
    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; build() }
    return MaterialCardView(this).apply {
        radius = dp(10).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceContainer))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(24) }
        addView(inner)
    }
}

/** Hairline row separator: 1px, inset 16dp leading, flush trailing (iOS). */
fun LinearLayout.divider() {
    addView(
        View(context).apply {
            setBackgroundColor(context.color(R.color.separator))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { leftMargin = context.dp(16) }
        },
    )
}

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** Footnote 13, secondaryLabel — meta lines and inline empty states. */
fun Context.dimText(): TextView = TextView(this).apply {
    textSize = 13f
    setTextColor(color(R.color.label_secondary))
}

/** Section header: Footnote 13 ALL CAPS secondaryLabel, inset to row text. */
fun Context.sectionLabel(t: String) = TextView(this).apply {
    text = t
    isAllCaps = true
    textSize = 13f
    setTextColor(color(R.color.label_secondary))
    setPadding(dp(16), 0, dp(16), dp(7))
}

/** Section footer: Footnote 13 sentence case, sits under a card. */
fun Context.footerLabel(t: String) = dimText().apply {
    text = t
    setPadding(dp(16), 0, dp(16), 0)
    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = -dp(14); bottomMargin = dp(24) }
}

/** Grouped-list button row (the iOS "Sign Out" pattern): accent Body text. */
fun LinearLayout.buttonRow(label: String, onClick: () -> Unit): TextView {
    val row = TextView(context).apply {
        text = label
        textSize = 17f
        setTextColor(context.color(R.color.accent))
        minHeight = context.dp(44)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(16), context.dp(11), context.dp(16), context.dp(11))
        setBackgroundResource(context.resolveAttrRes(android.R.attr.selectableItemBackground))
        setOnClickListener { onClick() }
    }
    addView(row, fullWidth())
    return row
}

/** Tinted button (accent text on 15% accent fill) — dialog quick-actions. */
fun Context.tintedButton(label: String, onClick: () -> Unit) =
    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label
        strokeWidth = 0
        cornerRadius = dp(10)
        backgroundTintList = ColorStateList.valueOf(color(R.color.accent_tint))
        setTextColor(color(R.color.accent))
        minHeight = dp(44)
        setOnClickListener { onClick() }
    }

fun Context.resolveAttrRes(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.resourceId
}

/**
 * Value row with a borderless trailing field (iOS "label — value" cell):
 * Body 17 label leading, right-aligned Body 17 input, no boxes or floating
 * labels. The row is ≥44dp; the EditText keeps a comfortable touch area.
 */
fun LinearLayout.fieldRow(
    label: String,
    example: String,
    value: String,
    numeric: Boolean = false,
): EditText {
    val et = EditText(context).apply {
        id = View.generateViewId()
        setText(value)
        hint = example
        textSize = 17f
        background = null
        isSingleLine = true
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setTextColor(context.color(R.color.label))
        setHintTextColor(context.color(R.color.label_tertiary))
        inputType = if (numeric) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        setPadding(context.dp(12), context.dp(11), 0, context.dp(11))
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(44)
        setPadding(context.dp(16), 0, context.dp(16), 0)
        addView(
            TextView(context).apply {
                text = label
                textSize = 17f
                setTextColor(context.color(R.color.label))
                labelFor = et.id
            },
        )
        addView(et, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }
    addView(row)
    return et
}

/** iOS-tinted switch: white thumb, green/gray track, no M3 decorations. */
fun Context.iosSwitch(): MaterialSwitch = MaterialSwitch(this).apply {
    thumbTintList = ColorStateList.valueOf(Color.WHITE)
    thumbIconDrawable = null
    trackTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(color(R.color.ios_green), color(R.color.switch_track_off)),
    )
    trackDecorationTintList = ColorStateList.valueOf(Color.TRANSPARENT)
}

/** Toggle row: Body 17 title, Footnote 13 subtitle, trailing iOS switch. */
fun LinearLayout.switchRow(title: String, subtitle: String?, checked: Boolean): MaterialSwitch {
    val sw = context.iosSwitch().apply {
        isChecked = checked
        // The switch is the single a11y node; label it so TalkBack reads the
        // title/subtitle plus the on/off state.
        contentDescription = if (subtitle != null) "$title, $subtitle" else title
    }
    val texts = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            TextView(context).apply {
                text = title
                textSize = 17f
                setTextColor(context.color(R.color.label))
            },
        )
        if (subtitle != null) addView(context.dimText().apply { text = subtitle; setPadding(0, context.dp(1), 0, 0) })
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(44)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
        addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(sw)
        setOnClickListener { sw.toggle() }
    }
    addView(row)
    return sw
}

fun Context.toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
