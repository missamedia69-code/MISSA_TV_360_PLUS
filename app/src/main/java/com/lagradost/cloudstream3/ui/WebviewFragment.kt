package com.lagradost.cloudstream3.ui

import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.databinding.FragmentWebviewBinding
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppContextUtils.loadRepository
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus

class WebviewFragment : BaseFragment<FragmentWebviewBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentWebviewBinding::inflate)
) {

    override fun fixLayout(view: View) = Unit

    override fun onBindingCreated(binding: FragmentWebviewBinding) {
        val url = arguments?.getString(WEBVIEW_URL) ?: "".also {
            findNavController().popBackStack()
        }

        // SECURITE S3 : voir le commentaire sur addJavascriptInterface plus bas.
        val allowRepoBridge = arguments?.getBoolean(WEBVIEW_REPO_BRIDGE) == true

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val requestUrl = request?.url.toString()
                val performedAction = MainActivity.handleAppIntentUrl(activity, requestUrl, true)
                if (performedAction) {
                    findNavController().popBackStack()
                    return true
                }

                return super.shouldOverrideUrlLoading(view, request)
            }
        }

        binding.webView.apply {
            WebViewResolver.webViewUserAgent = settings.userAgentString

            // SECURITE S3 : ce pont permet à la page web affichée de faire apparaître le
            // dialogue « ajouter un dépôt », donc d'installer du code dans l'application.
            // Or ce WebView sert aussi de navigateur de repli pour n'importe quel lien
            // (openBrowser -> openWebView) : une page quelconque ne doit pas pouvoir
            // proposer l'installation d'une extension. Le pont n'est donc branché que si
            // l'appelant le demande explicitement.
            if (allowRepoBridge) {
                addJavascriptInterface(RepoApi(activity), "RepoApi")
            }
            settings.javaScriptEnabled = true
            settings.userAgentString = USER_AGENT
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false

            loadUrl(url)
        }
    }

    companion object {
        private const val WEBVIEW_URL = "webview_url"
        /** SECURITE S3 : n'accorde le pont JavaScript qu'aux écrans qui en ont besoin. */
        private const val WEBVIEW_REPO_BRIDGE = "webview_repo_bridge"

        fun newInstance(webViewUrl: String, allowRepoBridge: Boolean = false) =
            Bundle().apply {
                putString(WEBVIEW_URL, webViewUrl)
                putBoolean(WEBVIEW_REPO_BRIDGE, allowRepoBridge)
            }
    }

    private class RepoApi(val activity: FragmentActivity?) {
        @JavascriptInterface
        fun installRepo(repoUrl: String) {
            val sanitizedUrl = repoUrl.trim()
            if (!sanitizedUrl.startsWith("https://", ignoreCase = true)) return
            val hostActivity = activity ?: return
            hostActivity.runOnUiThread {
                if (hostActivity.isFinishing || hostActivity.isDestroyed) return@runOnUiThread
                AlertDialog.Builder(hostActivity)
                    .setTitle(R.string.add_repository)
                    .setMessage(sanitizedUrl)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        hostActivity.loadRepository(sanitizedUrl)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                    .setDefaultFocus()
            }
        }
    }
}
