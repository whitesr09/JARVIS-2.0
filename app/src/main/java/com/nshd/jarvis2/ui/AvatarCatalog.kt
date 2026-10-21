package com.nshd.jarvis2.ui

import androidx.annotation.DrawableRes
import com.nshd.jarvis2.R

/**
 * The 12 floating-overlay companions (MASTER-PLAN §2.8), ported from the
 * web simulator's original SVG designs into vector drawables. Each
 * drawable is ONLY the character/shape itself on transparency — there is
 * deliberately no background disc or plate behind any avatar.
 */
object AvatarCatalog {

    data class Avatar(val id: String, val label: String, @DrawableRes val resId: Int)

    val all: List<Avatar> = listOf(
        Avatar("alien", "Alien", R.drawable.avatar_alien),
        Avatar("blob", "Blob", R.drawable.avatar_blob),
        Avatar("cat", "Cat", R.drawable.avatar_cat),
        Avatar("circle", "Circle", R.drawable.avatar_circle),
        Avatar("cloud", "Cloud", R.drawable.avatar_cloud),
        Avatar("droid", "Droid", R.drawable.avatar_droid),
        Avatar("drop", "Drop", R.drawable.avatar_drop),
        Avatar("flower", "Flower", R.drawable.avatar_flower),
        Avatar("ghost", "Ghost", R.drawable.avatar_ghost),
        Avatar("hexagon", "Hexagon", R.drawable.avatar_hexagon),
        Avatar("mech", "Mech", R.drawable.avatar_mech),
        Avatar("pebble", "Pebble", R.drawable.avatar_pebble)
    )

    const val DEFAULT_ID = "droid"

    fun byId(id: String?): Avatar =
        all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }
}
