package io.gatekeeper.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import io.gatekeeper.R
import io.gatekeeper.services.ILoadIconCallback
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.PermissionGroups
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class AppListAdapter(
    private val service: IGatekeeperService,
    private val defaultIcon: Drawable
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.list_app_icon)
        private val title: TextView = view.findViewById(R.id.list_app_title)
        private val permsRow: LinearLayout = view.findViewById(R.id.list_app_perms_row)
        private val freeze: ImageView = view.findViewById(R.id.list_app_freeze)
        private val selectOrder: TextView = view.findViewById(R.id.list_app_select_order)
        private var itemIndex = -1

        init {
            view.setOnClickListener { onClick() }
            if (allowMultiSelect) {
                view.setOnLongClickListener { onLongClick() }
            }
        }

        /**
         * Состояние строки приложения профиля (вне режима выбора): под именем -- значки
         * объявленных им опасных разрешений (какие права запрашивает), справа -- кнопка
         * заморозки (снежинка) / разморозки (оттаивание), тап переключает. Список прав
         * добывается кросс-профильным IPC, поэтому кешируется и подгружается фоном --
         * строка сперва без разрешений, потом дорисовывает их, когда пришли.
         */
        private fun bindRowState(info: ApplicationInfoWrapper) {
            val show = workProfile && !multiSelectMode
            if (!show) {
                permsRow.visibility = View.GONE
                permsRow.removeAllViews()
                freeze.visibility = View.GONE
                return
            }
            val context = itemView.context
            val frozen = info.isHidden()

            freeze.visibility = View.VISIBLE
            freeze.setImageResource(if (frozen) R.drawable.ic_unfreeze else R.drawable.ic_freeze)
            freeze.contentDescription =
                context.getString(if (frozen) R.string.unfreeze_app else R.string.freeze_app)
            val tintAttr =
                if (frozen) com.google.android.material.R.attr.colorOnSurface
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            ImageViewCompat.setImageTintList(freeze, attrColor(context, tintAttr))
            freeze.setOnClickListener { freezeHandler?.invoke(list[itemIndex]) }

            val pkg = info.getPackageName()
            val cached = cachedPerms(pkg)
            renderPerms(cached)
            if (cached == null && !permsExecutor.isShutdown && beginPermsQuery(pkg)) {
                val boundIndex = itemIndex
                try {
                    permsExecutor.execute {
                        val icons = computePermIcons(pkg)
                        endPermsQuery(pkg, icons)
                        handler.post {
                            if (boundIndex == itemIndex && itemIndex >= 0 &&
                                list[itemIndex].getPackageName() == pkg
                            ) {
                                renderPerms(icons)
                            }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    // Адаптер отсоединяется, executor уже погашен -- права не нужны.
                    endPermsQuery(pkg, emptyList())
                }
            }
        }

        private fun renderPerms(icons: List<Int>?) {
            permsRow.removeAllViews()
            val context = itemView.context
            icons?.forEach { res -> permsRow.addView(makePermIcon(context, res)) }
            permsRow.visibility = if (permsRow.childCount > 0) View.VISIBLE else View.GONE
        }

        private fun onClick() {
            if (itemIndex == -1) return

            if (!multiSelectMode) {
                contextMenuHandler?.showContextMenu(list[itemIndex], itemView)
            } else {
                if (!selectedIndices.contains(itemIndex)) {
                    select()
                } else {
                    deselect()
                }
            }
        }

        private fun onLongClick(): Boolean {
            if (itemIndex == -1) return false

            if (!multiSelectMode && actionModeHandler?.createActionMode() == true) {
                multiSelectMode = true
                select()
                return true
            }
            return false
        }

        fun select() {
            selectedIndices.add(itemIndex)
            selectOrder.clearAnimation()
            selectOrder.startAnimation(
                AnimationUtils.loadAnimation(itemView.context, R.anim.scale_appear)
            )
            showSelectOrder()
        }

        fun deselect() {
            selectedIndices.remove(itemIndex)
            selectOrder.clearAnimation()
            setUnselectedBackground()
            val anim = AnimationUtils.loadAnimation(itemView.context, R.anim.scale_hide)
            anim.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    if (actionModeCancelHandler != null && selectedIndices.isEmpty()) {
                        actionModeCancelHandler!!.cancelActionMode()
                    }
                    notifyDataSetChanged()
                }
                override fun onAnimationRepeat(animation: Animation) {}
            })
            selectOrder.startAnimation(anim)
        }

        fun showSelectOrder() {
            itemView.setBackgroundResource(R.color.selectedAppBackground)
            selectOrder.visibility = View.VISIBLE
            selectOrder.text = (selectedIndices.indexOf(itemIndex) + 1).toString()
        }

        fun hideSelectOrder() {
            setUnselectedBackground()
            selectOrder.visibility = View.GONE
        }

        private fun setUnselectedBackground() {
            itemView.background = null
        }

        fun setIndex(itemIndex: Int) {
            this.itemIndex = itemIndex

            if (itemIndex >= 0) {
                selectOrder.clearAnimation()

                val info = list[itemIndex]
                title.text = info.getLabel()
                bindRowState(info)

                if (multiSelectMode && selectedIndices.contains(itemIndex)) {
                    showSelectOrder()
                } else {
                    hideSelectOrder()
                }

                if (iconCache.containsKey(info.getPackageName())) {
                    icon.setImageBitmap(iconCache[info.getPackageName()])
                } else {
                    icon.setImageDrawable(defaultIcon)
                    try {
                        service.loadIcon(info, object : ILoadIconCallback.Stub() {
                            override fun callback(iconBitmap: Bitmap) {
                                if (itemIndex == this@ViewHolder.itemIndex) {
                                    handler.post { icon.setImageBitmap(iconBitmap) }
                                }
                                synchronized(AppListAdapter::class.java) {
                                    iconCache[info.getPackageName()] = iconBitmap
                                }
                            }
                        })
                    } catch (_: RemoteException) {
                    }
                }
            }
        }
    }

    fun interface ContextMenuHandler {
        fun showContextMenu(info: ApplicationInfoWrapper, view: View)
    }

    fun interface ActionModeHandler {
        fun createActionMode(): Boolean
    }

    fun interface ActionModeCancelHandler {
        fun cancelActionMode()
    }

    private val origList = ArrayList<ApplicationInfoWrapper>()
    private val list = ArrayList<ApplicationInfoWrapper>()
    private var searchQuery: String? = null
    private val iconCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > MAX_ICON_CACHE_ENTRIES
        }
    }
    /** Иконка уже загружена для строки списка: лист действий берет ту же, без второго запроса. */
    fun cachedIcon(packageName: String): Bitmap? = iconCache[packageName]

    private var contextMenuHandler: ContextMenuHandler? = null
    private var actionModeHandler: ActionModeHandler? = null
    private var actionModeCancelHandler: ActionModeCancelHandler? = null
    private val handler = Handler(Looper.getMainLooper())
    private var workProfile = false
    private var autoFreezePackages: Set<String> = emptySet()

    /** Тап по кнопке заморозки/разморозки строки. Ставит фрагмент. */
    var freezeHandler: ((ApplicationInfoWrapper) -> Unit)? = null

    /** Пакет -> глифы объявленных им опасных разрешений. Права статичны (манифест), так что
     *  кеш живет до пересбора списка. Заполняется фоновым потоком, читается с UI. */
    private val permsCache = HashMap<String, List<Int>>()
    private val permsInFlight = HashSet<String>()
    private val permsExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private fun cachedPerms(pkg: String): List<Int>? = synchronized(permsCache) { permsCache[pkg] }

    /** true, если запрос по пакету надо ставить: его нет ни в кеше, ни в полете. */
    private fun beginPermsQuery(pkg: String): Boolean =
        synchronized(permsCache) { !permsCache.containsKey(pkg) && permsInFlight.add(pkg) }

    private fun endPermsQuery(pkg: String, icons: List<Int>) = synchronized(permsCache) {
        permsCache[pkg] = icons
        permsInFlight.remove(pkg)
    }

    /** Объявленные приложением опасные разрешения -> глифы групп. Кросс-профильный IPC. */
    private fun computePermIcons(pkg: String): List<Int> {
        val declared = try {
            service.getDeniablePermissions(pkg)?.toSet() ?: emptySet()
        } catch (_: RemoteException) {
            return emptyList()
        }
        return PermissionGroups.groupsFor(declared).map { it.iconRes }
    }

    private var allowMultiSelect = false
    private var multiSelectMode = false
    private val selectedIndices = ArrayList<Int>()

    fun setWorkProfile(workProfile: Boolean) {
        this.workProfile = workProfile
    }

    private fun attrColor(context: Context, attr: Int): ColorStateList {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        val color = if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
        return ColorStateList.valueOf(color)
    }

    /** Маленький приглушенный глиф разрешения для ряда под именем. */
    private fun makePermIcon(context: Context, iconRes: Int): ImageView {
        val size = dpToPx(context, PERM_ICON_DP)
        val view = ImageView(context)
        view.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dpToPx(context, PERM_ICON_GAP_DP)
        }
        view.setImageResource(iconRes)
        ImageViewCompat.setImageTintList(
            view,
            attrColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        return view
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    fun setAutoFreezePackages(packages: Set<String>?) {
        autoFreezePackages = packages ?: emptySet()
        notifyDataSetChanged()
    }

    fun setContextMenuHandler(handler: ContextMenuHandler?) {
        contextMenuHandler = handler
    }

    fun setActionModeHandler(handler: ActionModeHandler?) {
        actionModeHandler = handler
    }

    fun setActionModeCancelHandler(handler: ActionModeCancelHandler?) {
        actionModeCancelHandler = handler
    }

    fun allowMultiSelect() {
        allowMultiSelect = true
    }

    fun isMultiSelectMode(): Boolean = multiSelectMode

    fun cancelMultiSelectMode() {
        multiSelectMode = false
        selectedIndices.clear()
        notifyDataSetChanged()
    }

    fun getSelectedItems(): List<ApplicationInfoWrapper>? {
        if (!multiSelectMode) return null
        if (selectedIndices.isEmpty()) return null
        return selectedIndices.map { list[it] }
    }

    fun setData(apps: List<ApplicationInfoWrapper>) {
        origList.clear()
        list.clear()
        iconCache.clear()
        synchronized(permsCache) {
            permsCache.clear()
            permsInFlight.clear()
        }
        origList.addAll(apps)
        notifyChange()
    }

    fun setSearchQuery(query: String?) {
        searchQuery = query
        notifyChange()
    }

    private fun notifyChange() {
        list.clear()
        if (searchQuery == null) {
            list.addAll(origList)
        } else {
            list.addAll(
                origList.filter { app ->
                    app.getPackageName().lowercase().contains(searchQuery!!) ||
                        app.getLabel()!!.lowercase().contains(searchQuery!!)
                }
            )
        }
        notifyDataSetChanged()
        listChangedListener?.invoke()
    }

    /** true, пока в списке действует фильтр поиска: пустой список тогда значит «не найдено». */
    fun hasSearchQuery(): Boolean = searchQuery != null

    /** Дергается после каждой пересборки списка -- фрагмент по нему показывает пустое состояние. */
    var listChangedListener: (() -> Unit)? = null

    override fun getItemCount(): Int = list.size

    override fun onCreateViewHolder(viewGroup: ViewGroup, i: Int): ViewHolder {
        val inflater = LayoutInflater.from(viewGroup.context)
        val view = inflater.inflate(R.layout.app_list_item, viewGroup, false)
        return ViewHolder(view).also { it.setIndex(i) }
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, i: Int) {
        viewHolder.setIndex(i)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.setIndex(-1)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        // Адаптер пересоздается на каждый onCreateView фрагмента: гасим фоновый поток
        // запроса разрешений, чтобы он не висел недемоном после ухода с экрана.
        permsExecutor.shutdown()
    }

    companion object {
        private const val MAX_ICON_CACHE_ENTRIES = 80
        private const val PERM_ICON_DP = 16
        private const val PERM_ICON_GAP_DP = 6
    }
}
