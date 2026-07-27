package com.lifetrack.app.classification

import java.util.Locale

/**
 * One auto-categorization bucket: the category LifeTrack will create (if it doesn't already
 * exist) and the keywords that route a transaction into it.
 */
data class KeywordCategory(
    val name: String,
    val emoji: String,
    val colorHex: String,
    val keywords: List<String>
)

/**
 * Curated merchant -> category dictionary for Indian bank/UPI SMS text.
 *
 * DESIGN NOTES (read before editing)
 * -----------------------------------
 * 1. PRECISION OVER COVERAGE. Every keyword is matched with a word boundary on both sides
 *    (see [MerchantClassifier]), so "BUS" cannot match inside "BUSINESS" or "CAB" inside
 *    "CABLE" -- the boundary requires a non-letter/digit on each side. This means it is safe
 *    to add short, generic domain words (BUS, ATM, GYM, SPA, TOLL) without them bleeding into
 *    unrelated merchant names.
 *
 * 2. AMBIGUOUS BARE WORDS ARE DELIBERATELY OMITTED. "APOLLO" alone is not listed anywhere
 *    because Apollo Hospitals, Apollo Pharmacy and Apollo Tyres are different categories --
 *    only the compound forms ("APOLLO HOSPITAL", "APOLLO PHARMACY") are listed. If you want to
 *    add a new brand that has multiple businesses under one name, do the same: qualify it.
 *
 * 3. ORDER IS PRIORITY. [CATEGORIES] is scanned top-to-bottom and the FIRST matching keyword
 *    wins, across the whole list (not per-category). Keep more specific / less ambiguous
 *    categories earlier if you add new overlapping keywords.
 *
 * 4. THIS FILE HAS NO ANDROID / ROOM DEPENDENCY ON PURPOSE. It's pure data + a pure function,
 *    so it's easy to unit test and easy to hand-edit without touching the database layer.
 */
object MerchantKeywordRules {

    val CATEGORIES: List<KeywordCategory> = listOf(

        KeywordCategory(
            name = "Food & Dining", emoji = "🍔", colorHex = "#FF7043",
            keywords = listOf(
                "SWIGGY", "ZOMATO", "EATSURE", "FAASOS", "BEHROUZ BIRYANI", "BOX8",
                "DOMINOS", "DOMINO'S", "PIZZA HUT", "MCDONALD", "MC DONALD", "BURGER KING",
                "KFC", "SUBWAY", "STARBUCKS", "CCD", "CAFE COFFEE DAY", "BARBEQUE NATION",
                "BARBEQUE-NATION", "HALDIRAM", "DUNKIN", "BASKIN ROBBINS", "KEVENTERS",
                "WOW MOMO", "THEOBROMA", "CHAI POINT", "CHAAYOS", "RESTAURANT", "RESTOBAR",
                "FOOD COURT", "EATERY", "BIRYANI", "BAKERY", "SWEET SHOP", "TIFFIN SERVICE"
            )
        ),

        KeywordCategory(
            name = "Groceries", emoji = "🛒", colorHex = "#66BB6A",
            keywords = listOf(
                "BIGBASKET", "BLINKIT", "ZEPTO", "GROFERS", "JIOMART", "DMART", "D-MART",
                "AVENUE SUPERMART", "MORE SUPERMARKET", "MORE MEGASTORE", "RELIANCE FRESH",
                "RELIANCE SMART", "NATURE'S BASKET", "SPENCER'S RETAIL", "STAR BAZAAR",
                "SUPERMARKET", "HYPERMARKET", "KIRANA STORE", "GROCERY"
            )
        ),

        KeywordCategory(
            name = "Medical & Hospital", emoji = "🏥", colorHex = "#EF5350",
            keywords = listOf(
                "HOSPITAL", "NURSING HOME", "CLINIC", "DIAGNOSTIC", "DIAGNOSTICS",
                "PATHLAB", "PATHOLOGY LAB", "APOLLO HOSPITAL", "FORTIS HEALTHCARE",
                "FORTIS HOSPITAL", "MAX HEALTHCARE", "MAX HOSPITAL", "MANIPAL HOSPITAL",
                "NARAYANA HEALTH", "NARAYANA HRUDAYALAYA", "AIIMS", "MEDANTA",
                "COLUMBIA ASIA", "KIMS HOSPITAL", "CARE HOSPITAL", "YASHODA HOSPITAL",
                "RAINBOW HOSPITAL", "DENTAL CLINIC", "DENTAL CARE", "ORTHOPAEDIC",
                "ORTHOPEDIC", "PHYSIOTHERAPY", "DIALYSIS", "BLOOD BANK", "AMBULANCE"
            )
        ),

        KeywordCategory(
            name = "Pharmacy & Medicine", emoji = "💊", colorHex = "#AB47BC",
            keywords = listOf(
                "PHARMACY", "APOLLO PHARMACY", "MEDPLUS", "NETMEDS", "TATA 1MG", "1MG",
                "PHARMEASY", "WELLNESS FOREVER", "MEDICAL STORE", "MEDICAL SHOP",
                "CHEMIST", "DRUG STORE", "DRUGSTORE", "GENERIC MEDICINE"
            )
        ),

        KeywordCategory(
            name = "Transport & Travel", emoji = "🚌", colorHex = "#42A5F5",
            keywords = listOf(
                "OLA CABS", "OLA MONEY", "UBER", "RAPIDO", "NAMMA YATRI", "IRCTC",
                "INDIAN RAILWAY", "INDIAN RAILWAYS", "METRO RAIL", "METRO STATION",
                "BMTC", "DTC BUS", "MSRTC", "KSRTC", "APSRTC", "TSRTC", "REDBUS",
                "ABHIBUS", "FASTAG", "TOLL PLAZA", "TOLL", "AUTO RICKSHAW", "CAB BOOKING",
                "TAXI SERVICE", "BUS TICKET", "BUS FARE", "BUS", "PARKING FEE", "PARKING"
            )
        ),

        KeywordCategory(
            name = "Fuel", emoji = "⛽", colorHex = "#FFA726",
            keywords = listOf(
                "PETROL PUMP", "PETROL BUNK", "FILLING STATION", "HPCL", "BPCL", "IOCL",
                "INDIAN OIL", "SHELL PETROL", "SHELL FUEL", "NAYARA ENERGY", "FUEL STATION",
                "DIESEL", "PETROL"
            )
        ),

        KeywordCategory(
            name = "Movies & Entertainment", emoji = "🎬", colorHex = "#EC407A",
            keywords = listOf(
                "BOOKMYSHOW", "BOOK MY SHOW", "MOVIE TICKET", "MOVIE TICKETS", "PVR CINEMA",
                "PVR", "INOX", "CINEPOLIS", "CINEMA HALL", "MULTIPLEX", "NETFLIX",
                "PRIME VIDEO", "AMAZON PRIME VIDEO", "HOTSTAR", "DISNEY+ HOTSTAR", "SONYLIV",
                "ZEE5", "SPOTIFY", "GAANA", "JIOSAAVN", "YOUTUBE PREMIUM", "GAMING ZONE",
                "ARCADE"
            )
        ),

        KeywordCategory(
            name = "Shopping", emoji = "🛍️", colorHex = "#26A69A",
            keywords = listOf(
                "AMAZON.IN", "AMAZON PAY", "AMAZON RETAIL", "FLIPKART", "MYNTRA", "AJIO",
                "MEESHO", "NYKAA", "TATA CLIQ", "SHOPCLUES", "LIFESTYLE STORES",
                "SHOPPERS STOP", "WESTSIDE", "MAX FASHION", "ZARA", "DECATHLON",
                "PANTALOONS", "CENTRAL MALL", "BRAND FACTORY"
            )
        ),

        KeywordCategory(
            name = "Utilities & Bills", emoji = "💡", colorHex = "#FFCA28",
            keywords = listOf(
                "ELECTRICITY BILL", "ELECTRICITY BOARD", "BESCOM", "MSEB", "TNEB", "BSES",
                "TATA POWER", "ADANI ELECTRICITY", "WATER BILL", "GAS BILL", "LPG CYLINDER",
                "LPG BOOKING", "BROADBAND BILL", "WIFI BILL", "MOBILE RECHARGE",
                "PREPAID RECHARGE", "POSTPAID BILL", "AIRTEL RECHARGE", "JIO RECHARGE",
                "VODAFONE IDEA", "VI RECHARGE", "ACT FIBERNET", "DTH RECHARGE"
            )
        ),

        KeywordCategory(
            name = "Rent & Housing", emoji = "🏠", colorHex = "#8D6E63",
            keywords = listOf(
                "HOUSE RENT", "RENT PAYMENT", "NOBROKER", "NO BROKER", "MAINTENANCE CHARGES",
                "SOCIETY MAINTENANCE", "HOUSING SOCIETY", "APARTMENT MAINTENANCE"
            )
        ),

        KeywordCategory(
            name = "Education", emoji = "🎓", colorHex = "#5C6BC0",
            keywords = listOf(
                "TUITION FEE", "SCHOOL FEE", "SCHOOL FEES", "COLLEGE FEE", "COLLEGE FEES",
                "UDEMY", "COURSERA", "BYJU", "UNACADEMY", "VEDANTU", "EXAM FEE",
                "UNIVERSITY FEE", "EDUCATION LOAN"
            )
        ),

        KeywordCategory(
            name = "Insurance", emoji = "🛡️", colorHex = "#26C6DA",
            keywords = listOf(
                "LIC PREMIUM", "LIC OF INDIA", "INSURANCE PREMIUM", "POLICYBAZAAR",
                "HDFC LIFE", "ICICI PRUDENTIAL", "SBI LIFE", "STAR HEALTH INSURANCE",
                "BAJAJ ALLIANZ", "TATA AIG", "MAX BUPA", "CARE HEALTH INSURANCE"
            )
        ),

        KeywordCategory(
            name = "Fitness & Wellness", emoji = "💪", colorHex = "#66BB6A",
            keywords = listOf(
                "CULTFIT", "CULT.FIT", "CULT FIT", "GOLD'S GYM", "GYM MEMBERSHIP", "GYM FEE",
                "GYM", "YOGA CLASS", "ZUMBA CLASS", "SALON", "SPA"
            )
        ),

        KeywordCategory(
            name = "Investments", emoji = "📈", colorHex = "#7E57C2",
            keywords = listOf(
                "ZERODHA", "GROWW", "UPSTOX", "KUVERA", "COIN BY ZERODHA", "MUTUAL FUND",
                "SYSTEMATIC INVESTMENT", "SIP INSTALLMENT", "SIP INSTALMENT", "NPS CONTRIBUTION",
                "PPF DEPOSIT"
            )
        ),

        KeywordCategory(
            name = "ATM & Cash", emoji = "💵", colorHex = "#78909C",
            keywords = listOf(
                "ATM WITHDRAWAL", "ATM WDL", "CASH WITHDRAWAL", "CSH WDL", "CASH WDL"
            )
        )
    )

    /**
     * All (category, compiled regex) pairs, in priority order. Built once, on first access.
     * Internal rather than private: [MerchantClassifier] scans it directly.
     */
    internal val compiled: List<Pair<KeywordCategory, Regex>> by lazy {
        CATEGORIES.flatMap { cat ->
            cat.keywords
                // Longer keywords first WITHIN a category so a multi-word phrase is tried
                // before a shorter one that might be a substring of it.
                .sortedByDescending { it.length }
                .map { kw -> cat to wordBoundaryRegex(kw) }
        }
    }

    private fun wordBoundaryRegex(keyword: String): Regex {
        val normalizedKeyword = normalize(keyword)
        // Regex.escape protects punctuation inside keywords like "1MG" or "H&M" or "DOMINO'S".
        return Regex("(?<![A-Z0-9])" + Regex.escape(normalizedKeyword) + "(?![A-Z0-9])")
    }

    /** Uppercase; collapse everything that isn't a letter/digit/& into single spaces. */
    fun normalize(text: String): String =
        text.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9&]+"), " ")
            .trim()
            .replace(Regex(" +"), " ")
}

/**
 * Merchant-name / SMS-text auto-classifier.
 *
 * Deliberately conservative: returns null (no guess) rather than a low-confidence match. Callers
 * should only use this to fill in transactions that have NO category yet -- never to override an
 * existing one.
 */
object MerchantClassifier {

    /**
     * @param merchant the parsed merchant/display name, e.g. "SWIGGY" or "ATM WDL SBI"
     * @param rawSms the original SMS body, if available -- used as a fallback signal only, since
     *   it's noisier (contains bank boilerplate, reference numbers, etc).
     * @return the matched category definition, or null if nothing matched.
     */
    fun classify(merchant: String, rawSms: String? = null): KeywordCategory? {
        val merchantHay = MerchantKeywordRules.normalize(merchant)
        matchAgainst(merchantHay)?.let { return it }

        if (!rawSms.isNullOrBlank()) {
            val smsHay = MerchantKeywordRules.normalize(rawSms)
            matchAgainst(smsHay)?.let { return it }
        }
        return null
    }

    private fun matchAgainst(haystack: String): KeywordCategory? {
        for ((cat, regex) in MerchantKeywordRules.compiled) {
            if (regex.containsMatchIn(haystack)) return cat
        }
        return null
    }
}
