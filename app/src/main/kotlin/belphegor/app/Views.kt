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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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

/**
 * Caps its child at [maxWidth] so grouped lists keep a readable column on
 * wide/landscape screens (HIG layout margins); parent gravity centers it.
 */
class CappedFrame(context: Context, private val maxWidth: Int) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (size > maxWidth) {
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.getMode(widthMeasureSpec))
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}

const val CONTENT_MAX_WIDTH_DP = 640

/** At/above this system font scale, label/value rows stack vertically so the
 * value keeps room instead of colliding with its label (HIG Dynamic Type). */
const val LARGE_FONT_SCALE = 1.3f

fun Context.capped(child: View): CappedFrame =
    CappedFrame(this, dp(CONTENT_MAX_WIDTH_DP)).apply {
        addView(child, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

fun Context.scroll(child: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    addView(
        capped(child),
        FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL),
    )
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
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = context.dp(16) }
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
    setPaddingRelative(dp(16), 0, dp(16), dp(7))
}

/** Section footer: Footnote 13 sentence case, sits under a card. */
fun Context.footerLabel(t: String) = dimText().apply {
    text = t
    setPaddingRelative(dp(16), 0, dp(16), 0)
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
        setPaddingRelative(context.dp(16), context.dp(11), context.dp(16), context.dp(11))
        setBackgroundResource(context.resolveAttrRes(android.R.attr.selectableItemBackground))
        setOnClickListener { onClick() }
    }
    // TalkBack: announce as a button, not plain text (the row replaced a
    // MaterialButton in the redesign).
    ViewCompat.setAccessibilityDelegate(
        row,
        object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        },
    )
    addView(row, fullWidth())
    return row
}

/**
 * iOS "label -> value" disclosure row: leading label, trailing secondary value
 * plus a chevron; the whole row is tappable. Returns the value view so the
 * caller can refresh it after a picker.
 */
fun LinearLayout.navRow(label: String, value: String, onClick: () -> Unit): TextView {
    val valueView = TextView(context).apply {
        text = value
        textSize = 17f
        setTextColor(context.color(R.color.label_secondary))
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(44)
        setPaddingRelative(context.dp(16), context.dp(6), context.dp(16), context.dp(6))
        setBackgroundResource(context.resolveAttrRes(android.R.attr.selectableItemBackground))
        addView(
            TextView(context).apply {
                text = label
                textSize = 17f
                setTextColor(context.color(R.color.label))
            },
        )
        addView(valueView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(context.color(R.color.label_tertiary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(context.dp(22), context.dp(22)).apply { marginStart = context.dp(6) },
        )
        setOnClickListener { onClick() }
    }
    addView(row)
    return valueView
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
    // At large Dynamic Type sizes a horizontal label + right-aligned value
    // collide, so stack them (label above value) like iOS Settings does.
    val stacked = context.resources.configuration.fontScale >= LARGE_FONT_SCALE
    val et = EditText(context).apply {
        id = View.generateViewId()
        setText(value)
        hint = example
        textSize = 17f
        background = null
        isSingleLine = true
        gravity = (if (stacked) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        setTextColor(context.color(R.color.label))
        setHintTextColor(context.color(R.color.label_tertiary))
        inputType = if (numeric) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        setPaddingRelative(if (stacked) 0 else context.dp(12), context.dp(11), 0, context.dp(11))
    }
    val labelView = TextView(context).apply {
        text = label
        textSize = 17f
        setTextColor(context.color(R.color.label))
        labelFor = et.id
    }
    val row = LinearLayout(context).apply {
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = if (stacked) Gravity.START else Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(44)
        setPaddingRelative(context.dp(16), if (stacked) context.dp(6) else 0, context.dp(16), if (stacked) context.dp(6) else 0)
        if (stacked) {
            addView(labelView, fullWidth())
            addView(et, fullWidth())
        } else {
            addView(labelView)
            addView(et, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
    }
    addView(row)
    return et
}

/** Rounded tertiary-fill surface for standalone (dialog) fields. */
fun Context.fieldSurface(): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(10).toFloat()
    setColor(color(R.color.bg_field))
}

/**
 * Centered 20sp-medium dialog title (iOS alert style). M3 keeps its own
 * alertTitle wrap_content and left-aligned, so we replace it wholesale via
 * MaterialAlertDialogBuilder.setCustomTitle, matching M3's title padding.
 */
fun Context.dialogTitle(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 20f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(color(R.color.label))
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(dp(24), dp(20), dp(24), dp(2))
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
        setPaddingRelative(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
        addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(sw)
        setOnClickListener { sw.toggle() }
    }
    addView(row)
    return sw
}

fun Context.toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
