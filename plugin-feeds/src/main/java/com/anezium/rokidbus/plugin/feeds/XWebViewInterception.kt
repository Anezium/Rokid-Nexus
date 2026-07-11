package com.anezium.rokidbus.plugin.feeds

import java.net.URLDecoder
import java.net.URI

internal object XWebViewInterception {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.3"

    fun isHomeTimelineGraphQlUrl(url: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase().orEmpty()
        if (TRUSTED_HOST_SUFFIXES.none { host == it || host.endsWith(".$it") }) return false
        val decodedPath = runCatching {
            URLDecoder.decode(parsed.rawPath.orEmpty(), Charsets.UTF_8.name())
        }.getOrDefault(parsed.path.orEmpty())
        val pathSegments = decodedPath.split('/').filter(String::isNotBlank)
        return decodedPath.contains("/graphql/", ignoreCase = true) &&
            HOME_OPERATIONS.any { operation -> pathSegments.any { it.equals(operation, ignoreCase = true) } }
    }

    fun responseFingerprint(body: String): String = "${body.length}:${body.hashCode()}"

    fun javascript(): String = INTERCEPTION_JAVASCRIPT

    private val HOME_OPERATIONS = listOf("HomeTimeline", "HomeLatestTimeline")
    private val TRUSTED_HOST_SUFFIXES = listOf("x.com", "twitter.com")

    private const val INTERCEPTION_JAVASCRIPT = """
        (function() {
          if (window.__nexusXHomeTimelineInterceptor) return;
          window.__nexusXHomeTimelineInterceptor = true;

          const isHomeTimeline = function(value) {
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
                /\/(hometimeline|homelatesttimeline)(?:\/|$)/.test(path);
            } catch (_) {
              return false;
            }
          };
          const deliver = function(url, body) {
            if (!isHomeTimeline(url) || typeof body !== 'string') return;
            try { window.NexusXBridge.onHomeTimeline(String(url), body); } catch (_) {}
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
                  if (isHomeTimeline(url)) {
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
                  if (isHomeTimeline(url) && (xhr.responseType === '' || xhr.responseType === 'text')) {
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
