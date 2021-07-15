package com.kanedias.dybr.fair

import androidx.appcompat.app.AppCompatActivity

/**
 * Flavor-specific donation helper class. This manages menu option "Donate" in the main activity.
 *
 * @author Kanedias
 *
 * Created on 10.04.18
 */
class DonateHelper(private val activity: AppCompatActivity) {

    fun available() = false

    fun donate() {
        throw IllegalStateException("Google Play donations require non-commercial legal entity to be set up")
    }

}