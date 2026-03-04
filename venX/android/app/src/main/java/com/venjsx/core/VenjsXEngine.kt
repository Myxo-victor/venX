package com.venjsx.core

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class VenjsXEngine(
  private val context: Context,
  private val rootLayout: ViewGroup,
  private val bridge: WebView
) {
  companion object {
    private const val ANIM_SIGNATURE_TAG_KEY = 0x7F0B1001
    private const val USE_RECONCILIATION = false
  }

  private data class VNode(
    val tag: String,
    val props: JSONObject,
    val style: JSONObject?,
    val children: List<VNode>
  )

  private data class ResolvedMargins(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val autoHorizontal: Boolean,
    val autoVertical: Boolean
  )

  private var mountedScrollView: ScrollView? = null
  private var mountedRootView: View? = null
  private var previousTree: VNode? = null
  private val defaultTypeface: Typeface? by lazy { loadFontByNames(listOf("myfont", "ibm_plex_sans")) }
  private val fontAwesomeTypeface: Typeface? by lazy {
    loadFontByNames(
      listOf(
        "font_awesome_6_free_solid_900",
        "font_awesome",
        "fontawesome"
      )
    )
  }

  private val imageCache: LruCache<String, Bitmap> = run {
    val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    val cacheKb = max(1024, maxKb / 8)
    object : LruCache<String, Bitmap>(cacheKb) {
      override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
  }

  @JavascriptInterface
  fun processUINode(json: String) {
    try {
      val parsed = parseNode(JSONObject(json))
      (context as Activity).runOnUiThread {
        try {
          if (!USE_RECONCILIATION) {
            mountFreshTree(parsed)
            return@runOnUiThread
          }

          val oldTree = previousTree
          val oldRoot = mountedRootView
          val scroll = mountedScrollView

          if (oldTree == null || oldRoot == null || scroll == null) {
            mountFreshTree(parsed)
            return@runOnUiThread
          }

          val reconciled = reconcileNode(
            parent = scroll,
            currentView = oldRoot,
            oldNode = oldTree,
            newNode = parsed,
            indexInParent = 0
          )

          if (reconciled !== oldRoot) {
            if (scroll.childCount > 0) {
              scroll.removeViewAt(0)
            }
            scroll.addView(reconciled, 0)
          }

          mountedRootView = reconciled
          previousTree = parsed
        } catch (e: Exception) {
          e.printStackTrace()
          mountFreshTree(parsed)
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  @JavascriptInterface
  fun openExternalURL(url: String) {
    if (url.isBlank()) return
    val normalizedUrl = if (
      url.startsWith("http://", ignoreCase = true) ||
      url.startsWith("https://", ignoreCase = true)
    ) url else "https://$url"

    val uri = try {
      Uri.parse(normalizedUrl)
    } catch (_: Exception) {
      return
    }

    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
      context.startActivity(intent)
    } catch (_: Exception) {
      // Log error or handle exception
    }
  }

  private fun mountFreshTree(tree: VNode) {
    rootLayout.removeAllViews()
    val split = splitFixedNodes(tree)
    val flowTree = split.first ?: emptyFlowRootNode()
    val fixedNodes = split.second
    val rendered = renderNode(flowTree)
    val scrollView = ScrollView(context).apply {
      setFillViewport(false)
      isSmoothScrollingEnabled = true
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      addView(rendered)
    }

    val host = FrameLayout(context).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
      addView(
        scrollView,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
      )
    }

    if (fixedNodes.isNotEmpty()) {
      val overlay = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        isClickable = false
        isFocusable = false
      }

      fixedNodes
        .sortedBy { styleInt(it.style, "zIndex", 0) }
        .forEach { fixedNode ->
          val fixedView = renderNode(fixedNode)
          applyFixedLayoutParams(fixedView, fixedNode.style)
          overlay.addView(fixedView)
        }

      host.addView(
        overlay,
        FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
      )
    }

    rootLayout.addView(host)
    mountedScrollView = scrollView
    mountedRootView = rendered
    previousTree = tree
  }

  private fun emptyFlowRootNode(): VNode {
    val props = JSONObject().apply { put("style", JSONObject()) }
    return VNode(
      tag = "div",
      props = props,
      style = JSONObject(),
      children = emptyList()
    )
  }

  private fun splitFixedNodes(node: VNode): Pair<VNode?, List<VNode>> {
    if (isFixedPosition(node.style)) {
      return Pair(null, listOf(node))
    }

    if (node.children.isEmpty()) {
      return Pair(node, emptyList())
    }

    val keptChildren = ArrayList<VNode>(node.children.size)
    val fixedNodes = ArrayList<VNode>()
    for (child in node.children) {
      val splitChild = splitFixedNodes(child)
      val flowChild = splitChild.first
      if (flowChild != null) {
        keptChildren.add(flowChild)
      }
      fixedNodes.addAll(splitChild.second)
    }

    return Pair(node.copy(children = keptChildren), fixedNodes)
  }

  private fun isFixedPosition(style: JSONObject?): Boolean {
    if (style == null) return false
    return style.optString("position", "").trim().lowercase() == "fixed"
  }

  private fun applyFixedLayoutParams(view: View, style: JSONObject?) {
    val width = if (
      style != null &&
      !style.has("width") &&
      style.has("left") &&
      style.has("right")
    ) {
      ViewGroup.LayoutParams.MATCH_PARENT
    } else {
      sizeFromStyle(style, "width", ViewGroup.LayoutParams.WRAP_CONTENT, view)
    }

    val height = if (
      style != null &&
      !style.has("height") &&
      style.has("top") &&
      style.has("bottom")
    ) {
      ViewGroup.LayoutParams.MATCH_PARENT
    } else {
      sizeFromStyle(style, "height", ViewGroup.LayoutParams.WRAP_CONTENT, view)
    }

    val lp = FrameLayout.LayoutParams(width, height)

    val hasLeft = style?.has("left") == true
    val hasRight = style?.has("right") == true
    val hasTop = style?.has("top") == true
    val hasBottom = style?.has("bottom") == true

    val horizontalGravity = when {
      hasLeft && !hasRight -> Gravity.START
      hasRight && !hasLeft -> Gravity.END
      else -> Gravity.START
    }
    val verticalGravity = when {
      hasTop && !hasBottom -> Gravity.TOP
      hasBottom && !hasTop -> Gravity.BOTTOM
      else -> Gravity.TOP
    }
    lp.gravity = horizontalGravity or verticalGravity

    lp.leftMargin = if (hasLeft) dp(styleInt(style, "left", 0)) else 0
    lp.rightMargin = if (hasRight) dp(styleInt(style, "right", 0)) else 0
    lp.topMargin = if (hasTop) dp(styleInt(style, "top", 0)) else 0
    lp.bottomMargin = if (hasBottom) dp(styleInt(style, "bottom", 0)) else 0

    view.layoutParams = lp
  }

  private fun parseNode(raw: JSONObject): VNode {
    val tag = raw.optString("tag", "div")
    val props = raw.optJSONObject("props") ?: JSONObject()
    val style = props.optJSONObject("style")
    val childrenRaw = raw.optJSONArray("children") ?: JSONArray()
    val children = ArrayList<VNode>(childrenRaw.length())
    for (i in 0 until childrenRaw.length()) {
      val child = childrenRaw.optJSONObject(i) ?: continue
      children.add(parseNode(child))
    }
    return VNode(tag, props, style, children)
  }

  private fun reconcileNode(
    parent: ViewGroup,
    currentView: View,
    oldNode: VNode,
    newNode: VNode,
    indexInParent: Int
  ): View {
    if (oldNode.tag != newNode.tag || !isViewCompatibleWithNode(currentView, oldNode)) {
      return renderNode(newNode)
    }

    val oldProps = oldNode.props.toString()
    val newProps = newNode.props.toString()
    val childrenChanged = oldNode.children.size != newNode.children.size

    // Input node keeps a TextWatcher. Recreate only when props change to avoid duplicate listeners.
    if (newNode.tag == "input" && oldProps != newProps) {
      return renderNode(newNode)
    }

    // Only skip when this is a true leaf node with identical props.
    // Parent-level shallow checks can miss deep changes (route/content updates).
    if (oldProps == newProps && !childrenChanged && oldNode.children.isEmpty() && newNode.children.isEmpty()) {
      return currentView
    }

    bindView(currentView, newNode)

    if (currentView is ViewGroup) {
      val common = min(oldNode.children.size, newNode.children.size)
      for (i in 0 until common) {
        val childView = currentView.getChildAt(i) ?: run {
          return renderNode(newNode)
        }
        val updated = reconcileNode(
          parent = currentView,
          currentView = childView,
          oldNode = oldNode.children[i],
          newNode = newNode.children[i],
          indexInParent = i
        )
        if (updated !== childView) {
          currentView.removeViewAt(i)
          currentView.addView(updated, i)
        }
      }

      if (newNode.children.size > oldNode.children.size) {
        for (i in oldNode.children.size until newNode.children.size) {
          currentView.addView(renderNode(newNode.children[i]))
        }
      } else if (newNode.children.size < oldNode.children.size) {
        for (i in oldNode.children.size - 1 downTo newNode.children.size) {
          currentView.removeViewAt(i)
        }
      }
    }

    return currentView
  }

  private fun isViewCompatibleWithNode(view: View, node: VNode): Boolean {
    return when {
      isCheckboxNode(node) -> view is CheckBox
      isIconNode(node) -> view is TextView
      else -> when (node.tag) {
      "button" -> view is Button
      "text" -> view is TextView
      "a" -> view is TextView
      "input" -> view is EditText
      "select" -> view is Spinner
      "image" -> view is ImageView
      "activityIndicator" -> view is ProgressBar
      else -> view is LinearLayout
      }
    }
  }

  private fun isCheckboxNode(node: VNode): Boolean {
    return node.tag == "checkbox" ||
      (node.tag == "input" && node.props.optString("type", "").equals("checkbox", ignoreCase = true))
  }

  private fun isIconNode(node: VNode): Boolean {
    return node.tag == "icon"
  }

  private fun renderNode(node: VNode): View {
    if (node.tag == "div" && node.style?.optString("overflowX", "") == "scroll") {
      val horizontalScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT
        )
      }
      val content = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
      }

      setupClickEvent(horizontalScroll, node.props, node.tag)
      applyBaseStyle(horizontalScroll, node.style)
      applyAnimation(horizontalScroll, node.style)

      if (node.children.isNotEmpty()) {
        for (child in node.children) {
          content.addView(renderNode(child))
        }
      }

      horizontalScroll.addView(content)
      return horizontalScroll
    }

    val view = when {
      isCheckboxNode(node) -> CheckBox(context)
      isIconNode(node) -> TextView(context)
      else -> when (node.tag) {
      "button" -> Button(context)
      "text" -> TextView(context)
      "a" -> TextView(context)
      "input" -> EditText(context)
      "select" -> Spinner(context)
      "image" -> ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
      }
      "activityIndicator" -> ProgressBar(context).apply { isIndeterminate = true }
      else -> LinearLayout(context)
      }
    }

    bindView(view, node)

    if (view is ViewGroup && node.children.isNotEmpty() && node.tag != "select") {
      if (view is LinearLayout) {
        addChildrenWithJustifySpacing(view, node)
      } else {
        for (child in node.children) {
          view.addView(renderNode(child))
        }
      }
    }

    return view
  }

  private fun bindView(view: View, node: VNode) {
    when {
      isCheckboxNode(node) -> {
        val checkbox = view as CheckBox
        checkbox.text = node.props.optString("textContent", node.props.optString("label", ""))
        val checked = when {
          node.props.has("checked") -> node.props.optBoolean("checked", false)
          else -> node.props.optString("value", "false").equals("true", ignoreCase = true)
        }
        if (checkbox.isChecked != checked) {
          checkbox.isChecked = checked
        }
        applyTextStyle(checkbox, node.style)
        setupCheckboxChangeEvent(checkbox, node.props)
        setupClickEvent(checkbox, node.props, "checkbox")
      }

      isIconNode(node) -> {
        val tv = view as TextView
        val iconName = node.props.optString("name", "").trim().lowercase()
        val faGlyph = node.props.optString("textContent", "")
        val fallbackGlyph = when (iconName) {
          "search" -> "\u2315"
          "bell" -> "\u25CE"
          "cog", "settings" -> "\u2699"
          "home" -> "\u2302"
          "user" -> "\u25CB"
          "star" -> "\u2605"
          "heart" -> "\u2665"
          "plus" -> "+"
          "minus" -> "-"
          "check" -> "\u2713"
          "close", "times" -> "\u2715"
          else -> faGlyph
        }
        tv.text = if (fontAwesomeTypeface == null) fallbackGlyph else faGlyph
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        if (fontAwesomeTypeface == null) {
          applyTextStyle(tv, node.style)
        } else {
          applyTextStyle(tv, mergedStyle(node.style, "Font Awesome 6 Free"))
        }
        setupClickEvent(tv, node.props, node.tag)
      }

      node.tag == "button" -> {
        val btn = view as Button
        btn.text = node.props.optString("textContent", "")
        btn.isAllCaps = false
        applyTextStyle(btn, node.style)
        setupClickEvent(btn, node.props, node.tag)
      }

      node.tag == "text" -> {
        val tv = view as TextView
        tv.text = node.props.optString("textContent", "")
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        applyTextStyle(tv, node.style)
        setupClickEvent(tv, node.props, node.tag)
      }

      node.tag == "a" -> {
        val tv = view as TextView
        tv.text = node.props.optString(
          "textContent",
          node.props.optString("label", node.props.optString("href", ""))
        )
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        applyTextStyle(tv, mergedStyle(node.style, "myfont").apply {
          if (!has("color")) {
            put("color", "#0B5DFF")
          }
        })
        setupClickEvent(tv, node.props, node.tag)
      }

      node.tag == "input" -> {
        val input = view as EditText
        applyInputType(input, node.props)
        val nextHint = node.props.optString("placeholder", "")
        if (input.hint?.toString() != nextHint) {
          input.hint = nextHint
        }
        val nextValue = node.props.optString("value", "")
        if (input.text?.toString() != nextValue) {
          input.setText(nextValue)
          input.setSelection(input.text?.length ?: 0)
        }
        applyTextStyle(input, node.style)
        setupChangeEvent(input, node.props, node.tag)
        setupClickEvent(input, node.props, node.tag)
      }

      node.tag == "select" -> {
        val spinner = view as Spinner
        bindSelect(spinner, node)
      }

      node.tag == "image" -> {
        val image = view as ImageView
        val src = node.props.optString("src", "")
        if ((image.tag as? String) != src) {
          image.tag = src
          loadImageSource(image, src)
        }
        setupClickEvent(image, node.props, node.tag)
      }

      node.tag == "activityIndicator" -> {
        setupClickEvent(view, node.props, node.tag)
      }

      else -> {
        val layout = view as LinearLayout
        val direction = node.style?.optString("flexDirection", "column") ?: "column"
        layout.orientation = if (direction == "row") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        setupClickEvent(layout, node.props, node.tag)
      }
    }

    applyBaseStyle(view, node.style)
    applyLayoutStyle(view, node.style)
    applyAnimation(view, node.style)
  }

  private fun bindSelect(spinner: Spinner, node: VNode) {
    val optionNodes = node.children.filter { it.tag == "option" }
    val optionValues = optionNodes.map { option ->
      val label = extractOptionText(option)
      option.props.optString("value", label)
    }
    val optionLabels = optionNodes.map { option ->
      val extracted = extractOptionText(option)
      if (extracted.isBlank()) option.props.optString("value", "") else extracted
    }

    val safeLabels = if (optionLabels.isEmpty()) listOf("") else optionLabels
    val adapter = object : ArrayAdapter<String>(
      context,
      android.R.layout.simple_spinner_item,
      safeLabels
    ) {
      override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getView(position, convertView, parent)
        if (row is TextView) {
          row.setTextColor(Color.parseColor("#111111"))
        }
        return row
      }

      override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = super.getDropDownView(position, convertView, parent)
        if (row is TextView) {
          row.setTextColor(Color.parseColor("#111111"))
        }
        return row
      }
    }
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    spinner.adapter = adapter

    if (optionValues.isNotEmpty()) {
      val selectedValue = node.props.optString("value", "")
      val selectedIndex = optionValues.indexOf(selectedValue).takeIf { it >= 0 } ?: 0
      if (spinner.selectedItemPosition != selectedIndex) {
        try {
          spinner.setSelection(selectedIndex, false)
        } catch (_: Exception) {
          spinner.setSelection(0, false)
        }
      }
    }

    setupSelectChangeEvent(spinner, node.props, optionValues, optionLabels)
  }

  private fun addChildrenWithJustifySpacing(layout: LinearLayout, node: VNode) {
    val children = node.children
    val justify = node.style?.optString("justifyContent", "")?.trim()?.lowercase() ?: ""
    val hasMultipleChildren = children.size > 1
    val needsSpaceBetween = hasMultipleChildren && justify == "space-between"
    val needsSpaceAround = hasMultipleChildren && justify == "space-around"
    val needsSpaceEvenly = hasMultipleChildren && justify == "space-evenly"

    if (!needsSpaceBetween && !needsSpaceAround && !needsSpaceEvenly) {
      for (child in children) {
        layout.addView(renderNode(child))
      }
      return
    }

    val edgeWeight = when {
      needsSpaceAround -> 0.5f
      needsSpaceEvenly -> 1f
      else -> 0f
    }
    val betweenWeight = 1f

    if (edgeWeight > 0f) {
      layout.addView(createFlexSpacer(layout.orientation, edgeWeight))
    }

    children.forEachIndexed { index, child ->
      layout.addView(renderNode(child))
      if (index < children.lastIndex) {
        layout.addView(createFlexSpacer(layout.orientation, betweenWeight))
      }
    }

    if (edgeWeight > 0f) {
      layout.addView(createFlexSpacer(layout.orientation, edgeWeight))
    }
  }

  private fun createFlexSpacer(orientation: Int, weight: Float): View {
    val spacer = View(context)
    spacer.layoutParams = if (orientation == LinearLayout.HORIZONTAL) {
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
    } else {
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, weight)
    }
    return spacer
  }

  private fun extractOptionText(node: VNode): String {
    val direct = node.props.optString("textContent", "").trim()
    if (direct.isNotEmpty()) return direct
    val label = node.props.optString("label", "").trim()
    if (label.isNotEmpty()) return label

    for (child in node.children) {
      if (child.tag == "text") {
        val value = child.props.optString("textContent", "").trim()
        if (value.isNotEmpty()) return value
      }
    }
    return node.props.optString("value", "")
  }

  private fun setupSelectChangeEvent(
    spinner: Spinner,
    props: JSONObject,
    optionValues: List<String>,
    optionLabels: List<String>
  ) {
    val events = props.optJSONObject("events")
    val changeEventId = events?.optInt("change", -1) ?: -1
    spinner.onItemSelectedListener = null
    if (changeEventId <= 0) return

    val currentValue = props.optString("value", "")
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (position < 0 || position >= optionValues.size) return
        val value = optionValues[position]
        if (value == currentValue) return
        try {
          val payload = JSONObject().apply {
            put("type", "change")
            put("tag", "select")
            put("platform", "android")
            put("value", value)
            put("label", optionLabels.getOrElse(position) { value })
            put("index", position)
            put("timestamp", System.currentTimeMillis())
          }
          emitEvent(changeEventId, payload)
        } catch (_: Exception) {
        }
      }

      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
  }

  private fun setupClickEvent(view: View, props: JSONObject, tag: String) {
    if (view is AdapterView<*>) {
      // AdapterView (e.g., Spinner) throws if setOnClickListener is used directly.
      return
    }

    val events = props.optJSONObject("events")
    val clickEventId = events?.optInt("click", -1) ?: -1
    val href = if (tag == "a") props.optString("href", "").trim() else ""
    val shouldOpenLink = href.isNotEmpty()
    if (clickEventId <= 0 && !shouldOpenLink) {
      view.setOnClickListener(null)
      return
    }

    view.setOnClickListener {
      try {
        if (shouldOpenLink) {
          openUrlInChrome(href)
        }
        val payload = JSONObject().apply {
          put("type", "click")
          put("tag", tag)
          put("platform", "android")
          if (shouldOpenLink) {
            put("href", href)
          }
          put("timestamp", System.currentTimeMillis())
        }
        if (clickEventId > 0) {
          emitEvent(clickEventId, payload)
        }
      } catch (_: Exception) {
      }
    }
  }

  private fun openUrlInChrome(rawUrl: String) {
    if (rawUrl.isBlank()) return
    val normalizedUrl = if (
      rawUrl.startsWith("http://", ignoreCase = true) ||
      rawUrl.startsWith("https://", ignoreCase = true)
    ) rawUrl else "https://$rawUrl"

    val uri = try {
      Uri.parse(normalizedUrl)
    } catch (_: Exception) {
      return
    }

    val chromeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
      setPackage("com.android.chrome")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
      context.startActivity(chromeIntent)
    } catch (_: ActivityNotFoundException) {
      try {
        context.startActivity(fallbackIntent)
      } catch (_: Exception) {
      }
    } catch (_: Exception) {
    }
  }

  private fun setupChangeEvent(input: EditText, props: JSONObject, tag: String) {
    val events = props.optJSONObject("events") ?: return
    if (!events.has("change")) return
    val changeEventId = events.optInt("change", -1)
    if (changeEventId <= 0) return

    input.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        try {
          val payload = JSONObject().apply {
            put("type", "change")
            put("tag", tag)
            put("platform", "android")
            put("value", s?.toString() ?: "")
            put("timestamp", System.currentTimeMillis())
          }
          emitEvent(changeEventId, payload)
        } catch (_: Exception) {
        }
      }

      override fun afterTextChanged(s: Editable?) {}
    })
  }

  private fun setupCheckboxChangeEvent(input: CheckBox, props: JSONObject) {
    val events = props.optJSONObject("events")
    val changeEventId = events?.optInt("change", -1) ?: -1
    input.setOnCheckedChangeListener(null)
    if (changeEventId <= 0) {
      return
    }

    input.setOnCheckedChangeListener { _, isChecked ->
      try {
        val payload = JSONObject().apply {
          put("type", "change")
          put("tag", "checkbox")
          put("platform", "android")
          put("checked", isChecked)
          put("value", isChecked)
          put("timestamp", System.currentTimeMillis())
        }
        emitEvent(changeEventId, payload)
      } catch (_: Exception) {
      }
    }
  }

  private fun loadImageSource(imageView: ImageView, src: String?) {
    if (src.isNullOrBlank()) return
    val source = src.trim()

    imageCache.get(source)?.let {
      imageView.setImageBitmap(it)
      return
    }

    if (source.startsWith("http://") || source.startsWith("https://")) {
      thread(start = true) {
        var connection: HttpURLConnection? = null
        try {
          val url = URL(source)
          connection = url.openConnection() as HttpURLConnection
          connection.doInput = true
          connection.connect()
          val bytes = connection.inputStream.use { it.readBytes() }
          val bitmap = decodeSampledBitmap(bytes, 1080, 720)
          if (bitmap != null) {
            imageCache.put(source, bitmap)
            (context as Activity).runOnUiThread { imageView.setImageBitmap(bitmap) }
          }
        } catch (_: Exception) {
        } finally {
          connection?.disconnect()
        }
      }
      return
    }

    thread(start = true) {
      val bitmap = loadAssetBitmap(source)
      if (bitmap != null) {
        imageCache.put(source, bitmap)
        (context as Activity).runOnUiThread { imageView.setImageBitmap(bitmap) }
      }
    }
  }

  private fun loadAssetBitmap(source: String): Bitmap? {
    val candidates = listOf(
      source,
      if (source.startsWith("./")) source.substring(2) else source,
      if (source.startsWith("images/")) "app/$source" else source,
      if (source.startsWith("./images/")) "app/${source.substring(2)}" else source,
      if (source.startsWith("app/")) source.substring(4) else source
    )

    for (candidate in candidates) {
      if (candidate.isBlank()) continue
      try {
        context.assets.open(candidate).use { input ->
          val bytes = input.readBytes()
          val bitmap = decodeSampledBitmap(bytes, 1080, 720)
          if (bitmap != null) return bitmap
        }
      } catch (_: Exception) {
      }
    }
    return null
  }

  private fun emitEvent(eventId: Int, payload: JSONObject) {
    val js = "window.__venjsDispatchNativeEvent && window.__venjsDispatchNativeEvent($eventId, $payload);"
    (context as Activity).runOnUiThread {
      bridge.evaluateJavascript(js, null)
    }
  }

  private fun applyBaseStyle(view: View, style: JSONObject?) {
    if (style == null) return

    val borderSpec = style.optString("border", "").trim()
    val borderWidthFromShorthand = parseBorderWidth(borderSpec)
    val borderColorFromShorthand = parseBorderColor(borderSpec)

    val hasRadius = style.has("borderRadius") || style.has("border-radius")
    val hasBackground = style.has("backgroundColor")
    val hasBorder = style.has("borderWidth") || style.has("borderColor") || borderSpec.isNotEmpty()

    if (hasRadius || hasBackground || hasBorder) {
      val drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(parseColor(style.optString("backgroundColor", "#00000000")))
        if (hasRadius) {
          cornerRadius = resolveBorderRadiusPx(style, view)
        }
      }

      if (hasBorder) {
        val borderWidth = if (style.has("borderWidth")) styleInt(style, "borderWidth", 0) else borderWidthFromShorthand
        val borderWidthPx = dp(borderWidth)
        val borderColor = if (style.has("borderColor")) {
          parseColor(style.optString("borderColor", "#00000000"))
        } else if (borderSpec.isNotEmpty()) {
          borderColorFromShorthand
        } else {
          parseColor("#000000")
        }
        drawable.setStroke(borderWidthPx, borderColor)
      }

      view.background = drawable

      if (hasRadius && isPercentBorderRadius(style)) {
        view.post {
          val bg = view.background as? GradientDrawable ?: return@post
          bg.cornerRadius = resolveBorderRadiusPx(style, view)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.invalidateOutline()
          }
        }
      }
    } else if (style.has("backgroundColor")) {
      view.setBackgroundColor(parseColor(style.optString("backgroundColor", "#00000000")))
    }

    val resolvedMargins = resolveMargins(style)
    val lp = LinearLayout.LayoutParams(
      sizeFromStyle(style, "width", ViewGroup.LayoutParams.MATCH_PARENT, view),
      sizeFromStyle(style, "height", ViewGroup.LayoutParams.WRAP_CONTENT, view)
    )
    lp.setMargins(
      dp(resolvedMargins.left),
      dp(resolvedMargins.top),
      dp(resolvedMargins.right),
      dp(resolvedMargins.bottom)
    )
    if (resolvedMargins.autoHorizontal && resolvedMargins.autoVertical) {
      lp.gravity = Gravity.CENTER
    } else if (resolvedMargins.autoHorizontal) {
      lp.gravity = Gravity.CENTER_HORIZONTAL
    } else if (resolvedMargins.autoVertical) {
      lp.gravity = Gravity.CENTER_VERTICAL
    }
    view.layoutParams = lp

    if ((style.has("borderRadius") || style.has("border-radius")) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
          val radiusPx = resolveBorderRadiusPx(style, v)
          outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
        }
      }
      view.clipToOutline = true
    }

    applyPadding(view, style)
  }

  private fun applyLayoutStyle(view: View, style: JSONObject?) {
    if (style == null) return

    when (style.optString("display", "").trim().lowercase()) {
      "none" -> {
        view.visibility = View.GONE
        return
      }
      "flex", "block", "" -> view.visibility = View.VISIBLE
      else -> view.visibility = View.VISIBLE
    }

    if (view is LinearLayout) {
      applyFlexContainerAlignment(view, style)
      return
    }

    if (view is TextView) {
      applyTextFlexAlignment(view, style)
    }
  }

  private fun applyFlexContainerAlignment(layout: LinearLayout, style: JSONObject) {
    val direction = style.optString("flexDirection", "column").trim().lowercase()
    val justify = style.optString("justifyContent", "").trim().lowercase()
    val align = style.optString("alignItems", "").trim().lowercase()
    val isRow = direction == "row"

    var horizontal = 0
    var vertical = 0

    if (isRow) {
      horizontal = mapHorizontalGravity(justify)
      vertical = mapVerticalGravity(align)
    } else {
      vertical = mapVerticalGravity(justify)
      horizontal = mapHorizontalGravity(align)
    }

    val resolvedGravity = horizontal or vertical
    if (resolvedGravity != 0) {
      layout.gravity = resolvedGravity
    }
  }

  private fun applyTextFlexAlignment(view: TextView, style: JSONObject) {
    val justify = style.optString("justifyContent", "").trim().lowercase()
    val align = style.optString("alignItems", "").trim().lowercase()

    val horizontal = mapHorizontalGravity(justify)
    val vertical = mapVerticalGravity(align)

    val resolvedGravity = horizontal or vertical
    if (resolvedGravity != 0) {
      view.gravity = resolvedGravity
    }
  }

  private fun mapHorizontalGravity(value: String): Int {
    return when (value) {
      "center" -> Gravity.CENTER_HORIZONTAL
      "flex-end" -> Gravity.END
      "flex-start" -> Gravity.START
      else -> 0
    }
  }

  private fun mapVerticalGravity(value: String): Int {
    return when (value) {
      "center" -> Gravity.CENTER_VERTICAL
      "flex-end" -> Gravity.BOTTOM
      "flex-start" -> Gravity.TOP
      else -> 0
    }
  }

  private fun applyTextStyle(view: TextView, style: JSONObject?) {
    val effectiveStyle = style ?: JSONObject()

    applyTypeface(view, effectiveStyle)

    if (effectiveStyle.has("fontSize")) {
      view.setTextSize(TypedValue.COMPLEX_UNIT_SP, styleInt(effectiveStyle, "fontSize", 16).toFloat())
    }
    if (effectiveStyle.has("color")) {
      view.setTextColor(parseColor(effectiveStyle.optString("color", "#111111")))
    }
    when (effectiveStyle.optString("textAlign", "")) {
      "center" -> view.gravity = Gravity.CENTER_HORIZONTAL
      "right" -> view.gravity = Gravity.END
      else -> view.gravity = Gravity.START
    }
  }

  private fun applyInputType(input: EditText, props: JSONObject) {
    val type = props.optString("type", "text").trim().lowercase()
    input.showSoftInputOnFocus = true
    input.setOnTouchListener(null)
    input.onFocusChangeListener = null
    input.isFocusable = true
    input.isFocusableInTouchMode = true
    input.isCursorVisible = true
    input.isLongClickable = true
    input.inputType = when (type) {
      "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
      "number" -> InputType.TYPE_CLASS_NUMBER
      "phone", "tel" -> InputType.TYPE_CLASS_PHONE
      "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      "multiline", "textarea" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      "select", "picker" -> {
        input.showSoftInputOnFocus = false
        input.isCursorVisible = false
        input.isLongClickable = false
        InputType.TYPE_NULL
      }
      "date" -> {
        bindDatePicker(input)
        InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
      }
      else -> InputType.TYPE_CLASS_TEXT
    }
  }

  private fun bindDatePicker(input: EditText) {
    input.showSoftInputOnFocus = false
    input.isFocusable = true
    input.isFocusableInTouchMode = true
    input.setOnTouchListener { _, event ->
      if (event.action == MotionEvent.ACTION_UP) {
        showDatePicker(input)
      }
      false
    }
    input.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
      if (hasFocus) {
        showDatePicker(input)
      }
    }
  }

  private fun showDatePicker(input: EditText) {
    val now = Calendar.getInstance()
    val dialog = DatePickerDialog(
      context,
      { _, year, month, dayOfMonth ->
        val value = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)
        input.setText(value)
        input.setSelection(value.length)
      },
      now.get(Calendar.YEAR),
      now.get(Calendar.MONTH),
      now.get(Calendar.DAY_OF_MONTH)
    )
    dialog.show()
  }

  private fun applyTypeface(view: TextView, style: JSONObject) {
    val explicitFamily = style.optString("fontFamily", "").trim()
    val baseTypeface = when {
      explicitFamily.equals("font awesome", ignoreCase = true) ||
        explicitFamily.equals("font awesome 6 free", ignoreCase = true) ||
        explicitFamily.equals("fontawesome", ignoreCase = true) -> fontAwesomeTypeface
      explicitFamily.isNotBlank() -> loadFontByNames(listOf(normalizeFontResourceName(explicitFamily)))
      else -> defaultTypeface
    } ?: defaultTypeface

    val weight = parseFontWeight(style.opt("fontWeight"))
    if (weight != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        view.typeface = Typeface.create(baseTypeface, weight, false)
      } else {
        val legacyStyle = if (weight >= 600) Typeface.BOLD else Typeface.NORMAL
        view.setTypeface(baseTypeface ?: view.typeface, legacyStyle)
      }
      return
    }

    if (baseTypeface != null) {
      view.typeface = baseTypeface
    }
  }

  private fun parseFontWeight(raw: Any?): Int? {
    return when (raw) {
      is Number -> raw.toInt().coerceIn(100, 900)
      is String -> {
        val cleaned = raw.trim().lowercase()
        when (cleaned) {
          "", "normal", "regular" -> 400
          "bold" -> 700
          else -> cleaned.toIntOrNull()?.coerceIn(100, 900)
        }
      }
      else -> null
    }
  }

  private fun loadFontByNames(names: List<String>): Typeface? {
    for (name in names) {
      val resourceName = normalizeFontResourceName(name)
      val resourceId = context.resources.getIdentifier(resourceName, "font", context.packageName)
      if (resourceId != 0) {
        try {
          val typeface = ResourcesCompat.getFont(context, resourceId)
          if (typeface != null) {
            return typeface
          }
        } catch (_: Exception) {
        }
      }
    }
    return null
  }

  private fun normalizeFontResourceName(name: String): String {
    return name.trim()
      .lowercase()
      .replace(Regex("[^a-z0-9_]+"), "_")
      .replace(Regex("_+"), "_")
      .trim('_')
  }

  private fun mergedStyle(style: JSONObject?, fallbackFamily: String): JSONObject {
    if (style == null) {
      return JSONObject().apply {
        put("fontFamily", fallbackFamily)
      }
    }
    if (style.has("fontFamily")) {
      return style
    }
    return JSONObject(style.toString()).apply {
      put("fontFamily", fallbackFamily)
    }
  }

  private fun sizeFromStyle(style: JSONObject?, key: String, fallback: Int, view: View): Int {
    if (style == null || !style.has(key)) return fallback

    val value = style.optString(key, "")
    if (value == "match" || value == "100%") return ViewGroup.LayoutParams.MATCH_PARENT
    if (value == "wrap" || value == "auto") {
      if (key == "height") {
        view.minimumHeight = 0
      }
      return ViewGroup.LayoutParams.WRAP_CONTENT
    }

    val base = if (fallback == ViewGroup.LayoutParams.MATCH_PARENT) 0 else 44
    return dp(styleInt(style, key, base))
  }

  private fun resolveMargins(style: JSONObject): ResolvedMargins {
    val marginRaw = style.optString("margin", "").trim()
    val marginTokens = parseBoxShorthand(marginRaw)

    var top = parsePxValue(marginTokens.getOrNull(0)) ?: 0
    var right = parsePxValue(marginTokens.getOrNull(1)) ?: top
    var bottom = parsePxValue(marginTokens.getOrNull(2)) ?: top
    var left = parsePxValue(marginTokens.getOrNull(3)) ?: right

    var autoTop = marginTokens.getOrNull(0)?.equals("auto", ignoreCase = true) == true
    var autoRight = marginTokens.getOrNull(1)?.equals("auto", ignoreCase = true) == true
    var autoBottom = marginTokens.getOrNull(2)?.equals("auto", ignoreCase = true) == true
    var autoLeft = marginTokens.getOrNull(3)?.equals("auto", ignoreCase = true) == true

    if (style.has("marginTop")) {
      autoTop = isAutoValue(style.opt("marginTop"))
      top = if (autoTop) 0 else styleInt(style, "marginTop", top)
    }
    if (style.has("marginRight")) {
      autoRight = isAutoValue(style.opt("marginRight"))
      right = if (autoRight) 0 else styleInt(style, "marginRight", right)
    }
    if (style.has("marginBottom")) {
      autoBottom = isAutoValue(style.opt("marginBottom"))
      bottom = if (autoBottom) 0 else styleInt(style, "marginBottom", bottom)
    }
    if (style.has("marginLeft")) {
      autoLeft = isAutoValue(style.opt("marginLeft"))
      left = if (autoLeft) 0 else styleInt(style, "marginLeft", left)
    }

    return ResolvedMargins(
      left = left,
      top = top,
      right = right,
      bottom = bottom,
      autoHorizontal = autoLeft && autoRight,
      autoVertical = autoTop && autoBottom
    )
  }

  private fun parseBoxShorthand(value: String): List<String> {
    if (value.isBlank()) return emptyList()
    val tokens = value.split(Regex("\\s+")).filter { it.isNotBlank() }
    return when (tokens.size) {
      1 -> listOf(tokens[0], tokens[0], tokens[0], tokens[0])
      2 -> listOf(tokens[0], tokens[1], tokens[0], tokens[1])
      3 -> listOf(tokens[0], tokens[1], tokens[2], tokens[1])
      else -> listOf(tokens[0], tokens[1], tokens[2], tokens[3])
    }
  }

  private fun parsePxValue(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    if (value.equals("auto", ignoreCase = true)) return null
    val cleaned = value.replace("px", "").trim()
    return cleaned.toDoubleOrNull()?.toInt()
  }

  private fun isAutoValue(raw: Any?): Boolean {
    return raw?.toString()?.trim()?.equals("auto", ignoreCase = true) == true
  }

  private fun parseBorderWidth(border: String): Int {
    if (border.isBlank()) return 0
    val tokens = border.split(Regex("\\s+")).filter { it.isNotBlank() }
    for (token in tokens) {
      val width = parsePxValue(token)
      if (width != null) {
        return width
      }
    }
    return 0
  }

  private fun parseBorderColor(border: String): Int {
    if (border.isBlank()) return Color.TRANSPARENT
    val tokens = Regex("""(?i)(rgba?\([^)]*\)|#[0-9a-f]{3,8}|[a-z]+)""")
      .findAll(border)
      .map { it.value.trim() }
      .filter { it.isNotBlank() }
      .toList()
    for (i in tokens.indices.reversed()) {
      val token = tokens[i]
      val parsed = parseColor(token)
      if (parsed != Color.TRANSPARENT || token.equals("transparent", ignoreCase = true)) {
        return parsed
      }
    }
    return parseColor("#000000")
  }

  private fun parseColor(value: String): Int {
    val raw = value.trim()
    if (raw.isEmpty()) return Color.TRANSPARENT

    val rgbMatch = Regex("""^rgb\(\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*\)$""", RegexOption.IGNORE_CASE)
      .find(raw)
    if (rgbMatch != null) {
      val r = rgbMatch.groupValues[1].toInt().coerceIn(0, 255)
      val g = rgbMatch.groupValues[2].toInt().coerceIn(0, 255)
      val b = rgbMatch.groupValues[3].toInt().coerceIn(0, 255)
      return Color.rgb(r, g, b)
    }

    val rgbaMatch = Regex("""^rgba\(\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*,\s*([0-9]{1,3})\s*,\s*([0-9]*\.?[0-9]+)\s*\)$""", RegexOption.IGNORE_CASE)
      .find(raw)
    if (rgbaMatch != null) {
      val r = rgbaMatch.groupValues[1].toInt().coerceIn(0, 255)
      val g = rgbaMatch.groupValues[2].toInt().coerceIn(0, 255)
      val b = rgbaMatch.groupValues[3].toInt().coerceIn(0, 255)
      val alphaRaw = rgbaMatch.groupValues[4].toFloatOrNull() ?: 1f
      val a = if (alphaRaw > 1f) alphaRaw.toInt().coerceIn(0, 255) else (alphaRaw * 255f).toInt().coerceIn(0, 255)
      return Color.argb(a, r, g, b)
    }

    return try {
      Color.parseColor(raw)
    } catch (_: Exception) {
      Color.TRANSPARENT
    }
  }

  private fun styleFloat(style: JSONObject?, key: String, fallback: Float): Float {
    if (style == null || !style.has(key)) return fallback
    return try {
      style.optString(key, fallback.toString())
        .replace("px", "")
        .trim()
        .toFloat()
    } catch (_: Exception) {
      fallback
    }
  }

  private fun isPercentBorderRadius(style: JSONObject?): Boolean {
    if (style == null) return false
    val raw = when {
      style.has("borderRadius") -> style.optString("borderRadius", "")
      style.has("border-radius") -> style.optString("border-radius", "")
      else -> ""
    }
    return raw.trim().endsWith("%")
  }

  private fun resolveBorderRadiusPx(style: JSONObject, view: View): Float {
    val raw = when {
      style.has("borderRadius") -> style.optString("borderRadius", "")
      style.has("border-radius") -> style.optString("border-radius", "")
      else -> ""
    }.trim()
    if (raw.isEmpty()) return 0f

    if (raw.endsWith("%")) {
      val percent = raw.removeSuffix("%").trim().toFloatOrNull() ?: return 0f
      val widthPx = view.width.toFloat()
      val heightPx = view.height.toFloat()
      val base = minOf(widthPx, heightPx)
      if (base <= 0f) return 0f
      return (base * (percent / 100f)).coerceAtLeast(0f)
    }

    val dpValue = raw
      .replace("px", "", ignoreCase = true)
      .trim()
      .toFloatOrNull() ?: return 0f
    return dpF(dpValue)
  }

  private fun styleInt(style: JSONObject?, key: String, fallback: Int): Int {
    if (style == null || !style.has(key)) return fallback
    return try {
      style.optString(key, fallback.toString())
        .replace("px", "")
        .trim()
        .toDouble()
        .toInt()
    } catch (_: Exception) {
      fallback
    }
  }

  private fun dp(value: Int): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value.toFloat(),
      context.resources.displayMetrics
    ).toInt()
  }

  private fun dpF(value: Float): Float {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP,
      value,
      context.resources.displayMetrics
    )
  }

  private fun applyPadding(view: View, style: JSONObject) {
    val basePadding = styleInt(style, "padding", 0)
    val horizontal = styleInt(style, "paddingHorizontal", basePadding)
    val vertical = styleInt(style, "paddingVertical", basePadding)

    val left = dp(styleInt(style, "paddingLeft", horizontal))
    val right = dp(styleInt(style, "paddingRight", horizontal))
    val top = dp(styleInt(style, "paddingTop", vertical))
    val bottom = dp(styleInt(style, "paddingBottom", vertical))

    if (left != view.paddingLeft || top != view.paddingTop || right != view.paddingRight || bottom != view.paddingBottom) {
      view.setPadding(left, top, right, bottom)
    }
  }

  private fun applyAnimation(view: View, style: JSONObject?) {
    if (style == null) return

    val type = style.optString("animation", style.optString("animationType", "")).trim()
    val duration = styleInt(style, "animationDuration", 280).toLong().coerceAtLeast(0L)
    val delay = styleInt(style, "animationDelay", 0).toLong().coerceAtLeast(0L)
    val distance = dp(styleInt(style, "animationDistance", 18)).toFloat()
    val targetOpacity = styleFloat(style, "opacity", 1f).coerceIn(0f, 1f)

    val signature = "${type}|${duration}|${delay}|${distance}|${targetOpacity}"
    if (view.getTag(ANIM_SIGNATURE_TAG_KEY) == signature) return
    view.setTag(ANIM_SIGNATURE_TAG_KEY, signature)

    if (type.isEmpty()) {
      view.alpha = targetOpacity
      return
    }

    when (type.lowercase()) {
      "fade", "fadein" -> {
        view.alpha = 0f
        view.animate()
          .alpha(targetOpacity)
          .setDuration(duration)
          .setStartDelay(delay)
          .start()
      }

      "slideup" -> {
        view.alpha = 0f
        view.translationY = distance
        view.animate()
          .translationY(0f)
          .alpha(targetOpacity)
          .setDuration(duration)
          .setStartDelay(delay)
          .start()
      }

      "slidedown" -> {
        view.alpha = 0f
        view.translationY = -distance
        view.animate()
          .translationY(0f)
          .alpha(targetOpacity)
          .setDuration(duration)
          .setStartDelay(delay)
          .start()
      }

      "zoomin" -> {
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate()
          .alpha(targetOpacity)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(duration)
          .setStartDelay(delay)
          .start()
      }

      else -> {
        view.alpha = targetOpacity
      }
    }
  }

  private fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    val options = BitmapFactory.Options().apply {
      inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
      inPreferredConfig = Bitmap.Config.RGB_565
      inDither = true
      inJustDecodeBounds = false
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
  }

  private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    var inSampleSize = 1
    val height = options.outHeight
    val width = options.outWidth

    if (height > reqHeight || width > reqWidth) {
      val halfHeight = height / 2
      val halfWidth = width / 2
      while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
        inSampleSize *= 2
      }
    }
    return max(1, inSampleSize)
  }
}
