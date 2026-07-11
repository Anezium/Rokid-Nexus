package com.anezium.rokidbus.plugin.feeds.xtransaction

// These values and bundle patterns are X-internal and can change without notice.
internal const val ADDITIONAL_RANDOM_NUMBER = 3
internal const val DEFAULT_KEYWORD = "obfiowerehiring"
internal const val TIME_EPOCH_SECONDS = 1_682_924_400L
internal const val ON_DEMAND_FILE_URL_TEMPLATE =
    "https://abs.twimg.com/responsive-web/client-web/ondemand.s.{filename}a.js"

internal val INDICES_REGEX = Regex("""(\(\w{1}\[(\d{1,2})],\s*16\))+""")
internal val ON_DEMAND_FILE_REGEX = Regex(""",(\d+):["']ondemand\.s["']""")
