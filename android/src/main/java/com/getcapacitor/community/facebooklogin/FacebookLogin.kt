package com.getcapacitor.community.facebooklogin

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.FacebookSdk
import com.facebook.GraphRequest
import com.facebook.appevents.AppEventsLogger
import com.facebook.internal.FragmentWrapper
import com.facebook.login.LoginConfiguration
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONException

@CapacitorPlugin
public class FacebookLogin : Plugin() {
    private lateinit var callbackManager: CallbackManager

    private lateinit var logger: AppEventsLogger

    @Volatile
    private var latestCallbackId: String? = null

    override fun load() {
        Log.d(logTag, "Entering load()")

        callbackManager = CallbackManager.Factory.create()
        logger = AppEventsLogger.newLogger(context)

        LoginManager.getInstance().registerCallback(
            callbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    Log.d(logTag, "LoginManager.onSuccess")

                    val callbackId = latestCallbackId
                    if (callbackId == null) {
                        Log.e(logTag, "LoginManager.onSuccess: no plugin saved call found.")
                    } else {
                        val ret = JSObject()
                        ret.put("accessToken", accessTokenToJson(result.accessToken))
                        ret.put("recentlyGrantedPermissions", collectionToJson(result.recentlyGrantedPermissions))
                        ret.put("recentlyDeniedPermissions", collectionToJson(result.recentlyDeniedPermissions))

                        finishSavedCall(callbackId) { it.resolve(ret) }
                    }
                }

                override fun onCancel() {
                    Log.d(logTag, "LoginManager.onCancel")

                    val callbackId = latestCallbackId
                    if (callbackId == null) {
                        Log.e(logTag, "LoginManager.onCancel: no plugin saved call found.")
                    } else {
                        val ret = JSObject()
                        ret.put("accessToken", null as String?)

                        finishSavedCall(callbackId) { it.resolve(ret) }
                    }
                }

                override fun onError(error: FacebookException) {
                    Log.e(logTag, "LoginManager.onError", error)

                    val callbackId = latestCallbackId
                    if (callbackId == null) {
                        Log.e(logTag, "LoginManager.onError: no plugin saved call found.")
                    } else {
                        finishSavedCall(callbackId) { it.reject(error.toString()) }
                    }
                }
            }
        )

        // After the app was recreated the fragment that started the login is restored and gets the result.
        activity.runOnUiThread {
            val fragment = activity.supportFragmentManager.findFragmentByTag(FacebookLoginResultFragment.TAG)
            (fragment as? FacebookLoginResultFragment)?.onResult = ::handleActivityResult
        }
    }

    private fun finishSavedCall(callbackId: String, finish: (PluginCall) -> Unit) {
        val savedCall = bridge.getSavedCall(callbackId)
        savedCall?.let(finish)

        latestCallbackId = null
        savedCall?.let { bridge.releaseCall(it) }
    }

    @PluginMethod
    public fun initialize(call: PluginCall) {
        call.resolve()
    }

    @PluginMethod
    public fun login(call: PluginCall) {
        Log.d(logTag, "Entering login()")

        if (latestCallbackId != null) {
            Log.e(logTag, "login: overlapped calls not supported")
            call.reject("Overlapped calls call not supported")
            return
        }

        val permissions: Collection<String> = try {
            // A missing array is reported like a malformed one, as it was when this was a NullPointerException.
            call.getArray("permissions")!!.toList()
        } catch (e: Exception) {
            Log.e(logTag, "login: invalid 'permissions' argument", e)
            call.reject("Invalid permissions argument")
            return
        }

        val nonce = call.getString("nonce", "") ?: ""

        val configuration = if (nonce.isEmpty()) {
            LoginConfiguration(permissions)
        } else {
            LoginConfiguration(permissions, hashWithSha256(nonce))
        }

        latestCallbackId = call.callbackId
        bridge.saveCall(call)
        activity.runOnUiThread { LoginManager.getInstance().logIn(FragmentWrapper(resultFragment()), configuration) }
    }

    @PluginMethod
    public fun logout(call: PluginCall) {
        Log.d(logTag, "Entering logout()")

        LoginManager.getInstance().logOut()

        call.resolve()
    }

    @PluginMethod
    public fun reauthorize(call: PluginCall) {
        Log.d(logTag, "Entering reauthorize()")

        if (latestCallbackId != null) {
            Log.e(logTag, "reauthorize: overlapped calls not supported")

            call.reject("Overlapped calls call not supported")

            return
        }

        latestCallbackId = call.callbackId
        bridge.saveCall(call)
        activity.runOnUiThread { LoginManager.getInstance().reauthorizeDataAccess(resultFragment()) }
    }

    @PluginMethod
    public fun getCurrentAccessToken(call: PluginCall) {
        Log.d(logTag, "Entering getCurrentAccessToken()")

        val accessToken = AccessToken.getCurrentAccessToken()

        val ret = JSObject()

        if (accessToken == null) {
            Log.d(logTag, "getCurrentAccessToken: accessToken is null")
        } else {
            Log.d(logTag, "getCurrentAccessToken: accessToken found")

            ret.put("accessToken", accessTokenToJson(accessToken))
        }

        call.resolve(ret)
    }

    @PluginMethod
    public fun getProfile(call: PluginCall) {
        Log.d(logTag, "Entering getProfile()")

        val accessToken = AccessToken.getCurrentAccessToken()

        if (accessToken == null) {
            Log.d(logTag, "getProfile: accessToken is null")
            call.reject("You're not logged in. Call FacebookLogin.login() first to obtain an access token.")

            return
        }

        if (accessToken.isExpired) {
            Log.d(logTag, "getProfile: accessToken is expired")
            call.reject("AccessToken is expired.")

            return
        }

        val parameters = Bundle()

        try {
            // Without fields this was a NullPointerException in the Java implementation as well.
            val fields = call.getArray("fields")!!
            val fieldsString = TextUtils.join(",", fields.toList<Any?>())

            parameters.putString("fields", fieldsString)
        } catch (e: JSONException) {
            call.reject("Can't handle fields", ex = e)

            return
        }

        val graphRequest = GraphRequest.newMeRequest(accessToken) { _, response ->
            val requestError = response?.error

            if (requestError != null) {
                call.reject(requestError.errorMessage)

                return@newMeRequest
            }

            try {
                val jsonObject = response?.getJSONObject() ?: throw JSONException("The response has no JSON object")

                call.resolve(JSObject.fromJSONObject(jsonObject))
            } catch (e: JSONException) {
                call.reject("Can't create response", ex = e)
            }
        }

        graphRequest.parameters = parameters
        graphRequest.executeAsync()
    }

    @PluginMethod
    public fun logEvent(call: PluginCall) {
        Log.d(logTag, "Entering logEvent()")
        val eventName = call.getString("eventName")
        if (eventName != null) {
            logger.logEvent(eventName, parametersToBundle(call.getObject("parameters")))
        }

        call.resolve()
    }

    @PluginMethod
    public fun setAutoLogAppEventsEnabled(call: PluginCall) {
        Log.d(logTag, "Entering setAutoLogAppEventsEnabled()")
        val enabled = call.getBoolean("enabled")
        if (enabled != null) {
            FacebookSdk.setAutoLogAppEventsEnabled(enabled)
        }
    }

    @PluginMethod
    public fun setAdvertiserIDCollectionEnabled(call: PluginCall) {
        Log.d(logTag, "Entering setAdvertiserIDCollectionEnabled()")
        val enabled = call.getBoolean("enabled")
        if (enabled != null) {
            FacebookSdk.setAdvertiserIDCollectionEnabled(enabled)
        }
    }

    // Must run on the main thread, where fragment transactions are allowed.
    private fun resultFragment(): FacebookLoginResultFragment {
        val fragmentManager = activity.supportFragmentManager
        val fragment = fragmentManager.findFragmentByTag(FacebookLoginResultFragment.TAG) as? FacebookLoginResultFragment
            ?: FacebookLoginResultFragment().also {
                fragmentManager.beginTransaction().add(it, FacebookLoginResultFragment.TAG).commitNowAllowingStateLoss()
            }
        fragment.onResult = ::handleActivityResult
        return fragment
    }

    private fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(logTag, "Entering handleOnActivityResult($requestCode, $resultCode)")

        if (callbackManager.onActivityResult(requestCode, resultCode, data)) {
            Log.d(logTag, "onActivityResult succeeded")
        } else {
            Log.w(logTag, "onActivityResult failed")
        }
    }

    /**
     * Convert date to ISO 8601 format.
     */
    private fun dateToJson(date: Date): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.ENGLISH).format(date)

    private fun collectionToJson(list: Collection<String?>): JSArray {
        val json = JSArray()

        for (item in list) {
            json.put(item)
        }

        return json
    }

    private fun parametersToBundle(parameters: JSObject?): Bundle {
        val bundle = Bundle()
        if (parameters == null) {
            return bundle
        }

        for (key in parameters.keys()) {
            when (val value = parameters.opt(key)) {
                is String -> bundle.putString(key, value)
                is Number -> bundle.putDouble(key, value.toDouble())
            }
        }

        return bundle
    }

    private fun accessTokenToJson(accessToken: AccessToken): JSObject {
        val ret = JSObject()
        ret.put("applicationId", accessToken.applicationId)
        ret.put("declinedPermissions", collectionToJson(accessToken.declinedPermissions))
        ret.put("expires", dateToJson(accessToken.expires))
        ret.put("lastRefresh", dateToJson(accessToken.lastRefresh))
        ret.put("permissions", collectionToJson(accessToken.permissions))
        ret.put("token", accessToken.token)
        ret.put("userId", accessToken.userId)
        ret.put("isExpired", accessToken.isExpired)

        return ret
    }

    public companion object {
        public const val FACEBOOK_SDK_REQUEST_CODE_OFFSET: Int = 0xface

        private fun hashWithSha256(input: String): String {
            try {
                // SHA-256 ハッシュを生成
                val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())

                // バイト配列を16進数文字列に変換
                return hash.joinToString("") { (0xff and it.toInt()).toString(16).padStart(2, '0') }
            } catch (e: NoSuchAlgorithmException) {
                throw RuntimeException("SHA-256アルゴリズムが見つかりません", e)
            }
        }
    }
}
