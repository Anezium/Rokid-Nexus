package com.anezium.rokidbus.plugin.feeds

import java.net.URLDecoder
import java.net.URI
import org.json.JSONObject

object XWebViewInterception {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.3"

    fun isHomeTimelineGraphQlUrl(url: String): Boolean {
        return graphQlOperation(url)?.let { operation ->
            HOME_OPERATIONS.any { it.equals(operation, ignoreCase = true) }
        } == true
    }

    fun isTweetDetailGraphQlUrl(url: String): Boolean =
        graphQlOperation(url)?.equals("TweetDetail", ignoreCase = true) == true

    fun tweetDetailFocalTweetId(url: String): String? {
        if (!isTweetDetailGraphQlUrl(url)) return null
        val query = runCatching { URI(url) }.getOrNull()?.rawQuery ?: return null
        val variables = query.split('&').firstNotNullOfOrNull { parameter ->
            val pieces = parameter.split('=', limit = 2)
            if (pieces.size != 2 || decode(pieces[0]) != "variables") null else decode(pieces[1])
        } ?: return null
        return runCatching { JSONObject(variables).optString("focalTweetId").trim() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    private fun graphQlOperation(url: String): String? {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        val host = parsed.host?.lowercase().orEmpty()
        if (TRUSTED_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") }) return null
        val decodedPath = runCatching {
            decode(parsed.rawPath.orEmpty())
        }.getOrDefault(parsed.path.orEmpty())
        val pathSegments = decodedPath.split('/').filter(String::isNotBlank)
        if (!decodedPath.contains("/graphql/", ignoreCase = true)) return null
        return pathSegments.lastOrNull()
    }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    fun responseFingerprint(body: String): String = "${body.length}:${body.hashCode()}"

    fun shouldSuppressDuplicate(
        previousFingerprint: String?,
        responseFingerprint: String,
        threadPostId: String?,
    ): Boolean = threadPostId == null && previousFingerprint == responseFingerprint

    fun javascript(): String = INTERCEPTION_JAVASCRIPT

    private val HOME_OPERATIONS = listOf("HomeTimeline", "HomeLatestTimeline")
    private val TRUSTED_HOST_SUFFIXES = listOf("x.com", "twitter.com")

    private const val INTERCEPTION_JAVASCRIPT = """
        (function() {
          if (window.__nexusXHomeTimelineInterceptor) return;
          window.__nexusXHomeTimelineInterceptor = true;

          const isCapturedGraphQl = function(value) {
            if (!value) return false;
            try {
              const parsed = new URL(String(value), window.location.href);
              const host = parsed.hostname.toLowerCase();
              const trustedHost = host === 'x.com' || host.endsWith('.x.com') ||
                host === 'twitter.com' || host.endsWith('.twitter.com');
              let path = parsed.pathname;
              try { path = decodeURIComponent(path); } catch (_) {}
              path = path.toLowerCase();
              return parsed.protocol === 'https:' && trustedHost && path.indexOf('/graphql/') !== -1 &&
                /\/(hometimeline|homelatesttimeline|tweetdetail)(?:\/|$)/.test(path);
            } catch (_) {
              return false;
            }
          };
          const deliver = function(url, body) {
            if (!isCapturedGraphQl(url) || typeof body !== 'string') return;
            try { window.NexusXBridge.onGraphQlResponse(String(url), body); } catch (_) {}
          };

          if (typeof window.fetch === 'function') {
            const originalFetch = window.fetch;
            window.fetch = function() {
              const args = arguments;
              return originalFetch.apply(this, args).then(function(response) {
                try {
                  const request = args[0];
                  const url = response.url ||
                    (typeof request === 'string' ? request : (request && request.url));
                  if (isCapturedGraphQl(url)) {
                    response.clone().text().then(function(body) { deliver(url, body); }).catch(function() {});
                  }
                } catch (_) {}
                return response;
              });
            };
          }

          const xhrPrototype = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
          if (xhrPrototype) {
            const originalOpen = xhrPrototype.open;
            const originalSend = xhrPrototype.send;
            xhrPrototype.open = function(method, url) {
              this.__nexusXRequestUrl = url;
              return originalOpen.apply(this, arguments);
            };
            xhrPrototype.send = function() {
              const xhr = this;
              xhr.addEventListener('load', function() {
                try {
                  const url = xhr.responseURL || xhr.__nexusXRequestUrl;
                  if (isCapturedGraphQl(url) && (xhr.responseType === '' || xhr.responseType === 'text')) {
                    deliver(url, xhr.responseText);
                  }
                } catch (_) {}
              }, { once: true });
              return originalSend.apply(this, arguments);
            };
          }
        })();
    """
}
