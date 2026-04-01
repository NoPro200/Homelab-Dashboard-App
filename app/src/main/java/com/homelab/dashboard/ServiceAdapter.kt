package com.homelab.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ServiceAdapter(
    private val allServices: MutableList<Service>,
    private val prefsManager: PreferencesManager,
    private val onServiceAction: (Service, Action) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class Action {
        OPEN, EDIT, DELETE, TOGGLE_FAVORITE, TOGGLE_HIDDEN
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SERVICE = 1
    }

    private val displayItems = mutableListOf<Any>()
    private val collapsedCategories = mutableSetOf<String>()
    private var searchQuery: String = ""
    private var hiddenInitialized = false
    
    // Network state — set from MainActivity
    var isOnVpn = false
    var isOnLocal = false

    data class CategoryHeader(
        val name: String, val count: Int, val isCollapsed: Boolean,
        val isFirst: Boolean = false, val isLast: Boolean = false,
        val isDefaultCollapsed: Boolean = false
    )

    var onCategoryOrderChanged: ((List<String>) -> Unit)? = null
    var onServicesReordered: (() -> Unit)? = null

    init {
        collapsedCategories.addAll(prefsManager.getDefaultCollapsed())
        rebuildDisplayList()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query.trim().lowercase()
        rebuildDisplayList()
    }

    fun rebuildDisplayList() {
        val oldItems = displayItems.toList()
        displayItems.clear()

        val filtered = if (searchQuery.isEmpty()) allServices.toList()
        else allServices.filter { svc ->
            svc.name.lowercase().contains(searchQuery) ||
            svc.url.lowercase().contains(searchQuery) ||
            (svc.category ?: "").lowercase().contains(searchQuery) ||
            (svc.packageName ?: "").lowercase().contains(searchQuery)
        }

        val visible = filtered.filter { !it.isHidden }
        val hidden = filtered.filter { it.isHidden }

        // Favorites
        val favorites = visible.filter { it.isFavorite }
        if (favorites.isNotEmpty()) {
            val key = "★ Favorites"
            displayItems.add(CategoryHeader(key, favorites.size, collapsedCategories.contains(key)))
            if (!collapsedCategories.contains(key)) displayItems.addAll(favorites)
        }

        // Ordered categories
        val allCats = visible.map { it.category ?: "Uncategorized" }.distinct()
        val savedOrder = prefsManager.getCategoryOrder()
        val defaultCollapsed = prefsManager.getDefaultCollapsed()
        val ordered = mutableListOf<String>()
        for (c in savedOrder) { if (c in allCats) ordered.add(c) }
        for (c in allCats.sorted()) { if (c !in ordered) ordered.add(c) }

        for ((i, cat) in ordered.withIndex()) {
            val svcs = visible.filter { (it.category ?: "Uncategorized") == cat }
                .sortedBy { it.sortOrder }
            if (svcs.isEmpty()) continue
            val collapsed = collapsedCategories.contains(cat)
            displayItems.add(CategoryHeader(cat, svcs.size, collapsed, i == 0, i == ordered.size - 1, cat in defaultCollapsed))
            if (!collapsed) displayItems.addAll(svcs)
        }

        // Hidden
        if (hidden.isNotEmpty()) {
            val key = "🚫 Hidden"
            if (!hiddenInitialized) { collapsedCategories.add(key); hiddenInitialized = true }
            val collapsed = collapsedCategories.contains(key)
            displayItems.add(CategoryHeader(key, hidden.size, collapsed))
            if (!collapsed) displayItems.addAll(hidden)
        }

        // DiffUtil for smooth updates
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = displayItems.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                val a = oldItems[o]; val b = displayItems[n]
                return when {
                    a is CategoryHeader && b is CategoryHeader -> a.name == b.name
                    a is Service && b is Service -> a.id == b.id
                    else -> false
                }
            }
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                val a = oldItems[o]; val b = displayItems[n]
                return a == b
            }
        })
        diff.dispatchUpdatesTo(this)
    }

    fun toggleCategory(cat: String) {
        if (cat in collapsedCategories) collapsedCategories.remove(cat) else collapsedCategories.add(cat)
        rebuildDisplayList()
    }

    fun expandAll() {
        collapsedCategories.clear()
        rebuildDisplayList()
    }

    fun collapseAll() {
        // Collect all category names currently in displayItems
        collapsedCategories.clear()
        for (item in displayItems) {
            if (item is CategoryHeader) collapsedCategories.add(item.name)
        }
        // Also collapse all known categories from services
        allServices.map { it.category ?: "Uncategorized" }.distinct().forEach { collapsedCategories.add(it) }
        collapsedCategories.add("★ Favorites")
        collapsedCategories.add("🚫 Hidden")
        rebuildDisplayList()
    }

    fun isAllExpanded(): Boolean = collapsedCategories.isEmpty()

    fun moveCategoryUp(cat: String) {
        val order = getCurrentCategoryOrder().toMutableList()
        val i = order.indexOf(cat)
        if (i > 0) { order.removeAt(i); order.add(i - 1, cat); prefsManager.saveCategoryOrder(order); rebuildDisplayList() }
    }

    fun moveCategoryDown(cat: String) {
        val order = getCurrentCategoryOrder().toMutableList()
        val i = order.indexOf(cat)
        if (i >= 0 && i < order.size - 1) { order.removeAt(i); order.add(i + 1, cat); prefsManager.saveCategoryOrder(order); rebuildDisplayList() }
    }

    fun toggleDefaultCollapsed(cat: String) {
        val set = prefsManager.getDefaultCollapsed().toMutableSet()
        if (cat in set) set.remove(cat) else set.add(cat)
        prefsManager.saveDefaultCollapsed(set)
        rebuildDisplayList()
    }

    fun renameCategory(oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        for (i in allServices.indices) {
            if ((allServices[i].category ?: "Uncategorized") == oldName) {
                allServices[i] = allServices[i].copy(category = newName)
            }
        }
        // Update category order
        val order = prefsManager.getCategoryOrder().toMutableList()
        val idx = order.indexOf(oldName)
        if (idx >= 0) order[idx] = newName
        prefsManager.saveCategoryOrder(order)
        // Update default collapsed
        val collapsed = prefsManager.getDefaultCollapsed().toMutableSet()
        if (oldName in collapsed) { collapsed.remove(oldName); collapsed.add(newName) }
        prefsManager.saveDefaultCollapsed(collapsed)
        
        onServicesReordered?.invoke()
        rebuildDisplayList()
    }

    fun moveServiceUp(service: Service) {
        val cat = service.category ?: "Uncategorized"
        val inCat = allServices.filter { (it.category ?: "Uncategorized") == cat && !it.isHidden }
            .sortedBy { it.sortOrder }
        val idx = inCat.indexOfFirst { it.id == service.id }
        if (idx <= 0) return
        swapSortOrder(inCat[idx], inCat[idx - 1])
    }

    fun moveServiceDown(service: Service) {
        val cat = service.category ?: "Uncategorized"
        val inCat = allServices.filter { (it.category ?: "Uncategorized") == cat && !it.isHidden }
            .sortedBy { it.sortOrder }
        val idx = inCat.indexOfFirst { it.id == service.id }
        if (idx < 0 || idx >= inCat.size - 1) return
        swapSortOrder(inCat[idx], inCat[idx + 1])
    }

    private fun swapSortOrder(a: Service, b: Service) {
        val ai = allServices.indexOfFirst { it.id == a.id }
        val bi = allServices.indexOfFirst { it.id == b.id }
        if (ai < 0 || bi < 0) return
        val orderA = allServices[ai].sortOrder
        val orderB = allServices[bi].sortOrder
        // If same sortOrder, assign sequential ones
        val newA = if (orderA == orderB) orderB + 1 else orderB
        val newB = if (orderA == orderB) orderA else orderA
        allServices[ai] = allServices[ai].copy(sortOrder = newA)
        allServices[bi] = allServices[bi].copy(sortOrder = newB)
        onServicesReordered?.invoke()
        rebuildDisplayList()
    }

    private fun getCurrentCategoryOrder(): List<String> {
        val cats = allServices.map { it.category ?: "Uncategorized" }.distinct()
        val saved = prefsManager.getCategoryOrder()
        val result = mutableListOf<String>()
        for (c in saved) { if (c in cats) result.add(c) }
        for (c in cats.sorted()) { if (c !in result) result.add(c) }
        return result
    }

    override fun getItemViewType(pos: Int) = if (displayItems[pos] is CategoryHeader) TYPE_HEADER else TYPE_SERVICE
    override fun getItemCount() = displayItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderViewHolder(inflater.inflate(R.layout.item_category_header, parent, false))
        else ServiceViewHolder(inflater.inflate(R.layout.item_service, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayItems[position]) {
            is CategoryHeader -> bindHeader(holder as HeaderViewHolder, item)
            is Service -> bindService(holder as ServiceViewHolder, item)
        }
    }

    private fun bindHeader(h: HeaderViewHolder, header: CategoryHeader) {
        h.tvCategoryName.text = header.name
        h.tvCategoryCount.text = header.count.toString()
        h.ivExpandCollapse.rotation = if (header.isCollapsed) -90f else 0f
        h.expandCollapseArea.setOnClickListener { toggleCategory(header.name) }

        val special = header.name.startsWith("★") || header.name.startsWith("🚫")
        h.btnMoveUp.visibility = if (special) View.GONE else View.VISIBLE
        h.btnMoveDown.visibility = if (special) View.GONE else View.VISIBLE
        h.btnCategoryMenu.visibility = if (special) View.GONE else View.VISIBLE
        h.btnMoveUp.alpha = if (header.isFirst) 0.3f else 1f
        h.btnMoveDown.alpha = if (header.isLast) 0.3f else 1f
        h.btnMoveUp.setOnClickListener { if (!header.isFirst) moveCategoryUp(header.name) }
        h.btnMoveDown.setOnClickListener { if (!header.isLast) moveCategoryDown(header.name) }

        h.btnCategoryMenu.setOnClickListener { view ->
            val ctx = view.context
            val collapseLabel = if (header.isDefaultCollapsed) "Don't collapse by default" else "Collapse by default"
            MaterialAlertDialogBuilder(ctx)
                .setTitle(header.name)
                .setItems(arrayOf("Rename Category", collapseLabel)) { _, which ->
                    when (which) {
                        0 -> showRenameCategoryDialog(ctx, header.name)
                        1 -> toggleDefaultCollapsed(header.name)
                    }
                }
                .show()
        }
    }

    private fun showRenameCategoryDialog(ctx: android.content.Context, oldName: String) {
        val input = EditText(ctx).apply {
            setText(oldName)
            setSelection(oldName.length)
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Rename Category")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                renameCategory(oldName, input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindService(h: ServiceViewHolder, service: Service) {
        h.tvServiceName.text = service.name
        h.tvServiceUrl.text = if (service.isApp) "App" else service.url

        h.btnFavorite.setImageResource(
            if (service.isFavorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
        h.btnFavorite.setOnClickListener { onServiceAction(service, Action.TOGGLE_FAVORITE) }

        // Public URL badge — show when we have a publicUrl and NOT on VPN/local
        val showPublic = !service.publicUrl.isNullOrEmpty() && !isOnVpn && !isOnLocal && !service.isApp
        h.tvPublicBadge.visibility = if (showPublic) View.VISIBLE else View.GONE

        IconCacheManager.loadServiceIcon(h.itemView.context, service, h.ivServiceIcon)
        h.serviceCard.setOnClickListener { onServiceAction(service, Action.OPEN) }
        h.btnMore.setOnClickListener { view -> showOptionsMenu(view, service) }
    }

    inner class HeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvCategoryName: TextView = v.findViewById(R.id.tvCategoryName)
        val tvCategoryCount: TextView = v.findViewById(R.id.tvCategoryCount)
        val ivExpandCollapse: ImageView = v.findViewById(R.id.ivExpandCollapse)
        val expandCollapseArea: View = v.findViewById(R.id.expandCollapseArea)
        val btnMoveUp: ImageButton = v.findViewById(R.id.btnMoveUp)
        val btnMoveDown: ImageButton = v.findViewById(R.id.btnMoveDown)
        val btnCategoryMenu: ImageButton = v.findViewById(R.id.btnCategoryMenu)
    }

    inner class ServiceViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val serviceCard: CardView = v.findViewById(R.id.serviceCard)
        val ivServiceIcon: ImageView = v.findViewById(R.id.ivServiceIcon)
        val tvServiceName: TextView = v.findViewById(R.id.tvServiceName)
        val tvServiceUrl: TextView = v.findViewById(R.id.tvServiceUrl)
        val btnMore: ImageButton = v.findViewById(R.id.btnMore)
        val btnFavorite: ImageButton = v.findViewById(R.id.btnFavorite)
        val tvPublicBadge: TextView = v.findViewById(R.id.tvPublicBadge)
    }

    private fun showOptionsMenu(view: View, service: Service) {
        val ctx = view.context
        val hideLabel = if (service.isHidden) "Show" else "Hide"
        val options = mutableListOf("Edit", "Move Up", "Move Down", hideLabel, "Delete")
        MaterialAlertDialogBuilder(ctx)
            .setTitle(service.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> onServiceAction(service, Action.EDIT)
                    1 -> moveServiceUp(service)
                    2 -> moveServiceDown(service)
                    3 -> onServiceAction(service, Action.TOGGLE_HIDDEN)
                    4 -> onServiceAction(service, Action.DELETE)
                }
            }
            .show()
    }
}
