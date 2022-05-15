package com.kanedias.dybr.fair.misc

import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import androidx.recyclerview.widget.RecyclerView
import com.ftinc.scoop.StyleLevel
import com.kanedias.dybr.fair.MainActivity
import com.kanedias.dybr.fair.R
import com.kanedias.dybr.fair.UserContentListFragment
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

fun FragmentActivity.showFullscreenFragment(frag: Fragment) {
    supportFragmentManager.beginTransaction()
            .addToBackStack("Showing fragment: $frag")
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .add(R.id.main_layout, frag)
            .commit()
}

fun RecyclerView.setMaxFlingVelocity(velocity: Int) {
    val field = RecyclerView::class.java.getDeclaredField("mMaxFlingVelocity")
    field.isAccessible = true
    field.setInt(this, velocity)
}

/**
 * Note: Doesn't work if view hierarchy is just being recreated, like when you launch application again
 * after its activities and fragments were already recycled.
 */
@Suppress("UNCHECKED_CAST")
fun <T: Fragment> FragmentActivity.getTopFragment(type: KClass<T>): T? {
    val clPredicate = { it: Fragment -> it::class.isSubclassOf(type) }
    return supportFragmentManager.fragments.reversed().find(clPredicate) as T?
}

fun styleLevelFromActivity(ctx: Context): StyleLevel? {
        val activity = ctx as? MainActivity ?: return null
        val fm = activity.supportFragmentManager

        // try to find fragment with style on top of backstack
        for (idx in fm.backStackEntryCount - 1 downTo 0) {
            val entry = fm.getBackStackEntryAt(idx)
            val opsField = FragmentTransaction::class.java.getDeclaredField("mOps").apply { isAccessible = true }
            val ops = opsField.get(entry) as? List<*>

            val lastOp = ops?.lastOrNull() ?: continue
            val fragField = lastOp::class.java.getDeclaredField("mFragment").apply { isAccessible = true }
            val fragment = fragField.get(lastOp) as? UserContentListFragment ?: continue

            return fragment.styleLevel
        }

        return activity.styleLevel
    }

val View.styleLevel : StyleLevel?
    get() {
        val activity = this.context as? MainActivity ?: return null
        val fm = activity.supportFragmentManager

        // first, check if this view's parent fragment is itself styled
        for (fragment in fm.fragments) {
            val styled = fragment as? UserContentListFragment ?: continue

            var view = this
            while (true) {
                val parent = view.parent
                if (parent === fragment.view)
                    return styled.styleLevel

                if (parent == null || parent !is View)
                    break

                view = parent
            }
        }

        // second, try to find fragment with style on top of backstack
        return styleLevelFromActivity(activity)
    }

/**
 * Resolve attribute effectively
 * @param attr attribute, for example [R.attr.toolbarPopupOverrideStyle]
 * @return resolved reference
 */
fun View.resolveAttr(attr: Int): Int {
    val typedValue = TypedValue()
    this.context.theme.resolveAttribute(attr, typedValue, true)
    return typedValue.data
}


/**
 * Resolve attribute effectively
 * @param attr attribute, for example [android.R.attr.colorPrimary]
 * @return resolved reference
 */
fun Context.resolveAttr(attr: Int): Int {
    val typedValue = TypedValue()
    this.theme.resolveAttribute(attr, typedValue, true)
    return typedValue.data
}