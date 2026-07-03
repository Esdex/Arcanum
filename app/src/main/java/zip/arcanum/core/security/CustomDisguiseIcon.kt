package zip.arcanum.core.security

enum class CustomDisguiseIcon(val prefValue: String) {
    APPS("apps"),
    ACCOUNT("account"),
    ALARM("alarm"),
    BRIEFCASE("briefcase"),
    BUILD("build"),
    BUSINESS("business"),
    CALENDAR("calendar"),
    CAMERA("camera"),
    CAR("car"),
    CLOUD("cloud"),
    COMPUTER("computer"),
    EMAIL("email"),
    FAVORITE("favorite"),
    FITNESS("fitness"),
    FLIGHT("flight"),
    HOME("home"),
    IMAGE("image"),
    KEY("key"),
    MAP("map"),
    MONEY("money"),
    MUSIC("music"),
    NOTIFICATIONS("notifications"),
    PHONE("phone"),
    PUBLIC("public"),
    SCHOOL("school"),
    SEARCH("search"),
    SETTINGS("settings"),
    SHOPPING("shopping"),
    SPORTS("sports"),
    STAR("star"),
    TIMER("timer"),
    WEATHER("weather"),
    BATTERY("battery"),
    WIFI("wifi"),
    CREDIT_CARD("credit_card"),
    RESTAURANT("restaurant"),
    HEALTH("health"),
    BOOK("book"),
    MOVIE("movie"),
    GAME("game"),
    TRAVEL("travel"),
    SHIPPING("shipping"),
    LIGHT("light"),
    STORAGE("storage"),
    DASHBOARD("dashboard"),
    EXPLORE("explore"),
    NOTE("note"),
    CALCULATOR("calculator"),
    SHIELD("shield"),
    HEADPHONES("headphones"),
    NEWS("news"),
    QR("qr");

    companion object {
        val default = APPS

        fun fromPrefValue(value: String?): CustomDisguiseIcon =
            entries.firstOrNull { it.prefValue == value } ?: default
    }
}
