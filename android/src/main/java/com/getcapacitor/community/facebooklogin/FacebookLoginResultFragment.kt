package com.getcapacitor.community.facebooklogin

import android.content.Intent
import androidx.fragment.app.Fragment

/**
 * Starts the Facebook login activity and receives its result.
 *
 * The Facebook SDK only offers login with a nonce and reauthorization for an activity or a fragment, and
 * starts its activity with a request code. The Capacitor runtime no longer forwards
 * Activity.onActivityResult to plugins, but a fragment still gets the result of what it started.
 */
public class FacebookLoginResultFragment : Fragment() {
    internal var onResult: ((requestCode: Int, resultCode: Int, data: Intent?) -> Unit)? = null

    @Deprecated("The Facebook SDK starts its activity with a request code")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        onResult?.invoke(requestCode, resultCode, data)
    }

    internal companion object {
        const val TAG = "CapacitorFacebookLoginResult"
    }
}
