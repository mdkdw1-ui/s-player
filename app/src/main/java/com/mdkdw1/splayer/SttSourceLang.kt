package com.mdkdw1.splayer

enum class SttSourceLang(
    val code: String,
    val displayName: String
) {
    AUTO("auto", "자동 감지"),
    ENGLISH("en", "영어"),
    JAPANESE("ja", "일본어"),
    CHINESE("zh", "중국어"),
    SPANISH("es", "스페인어")
}
