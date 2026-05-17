package com.zalopilot.app.util

/**
 * Câu bình luận mặc định: có emoji, phần chữ tối đa 4 từ.
 * Dùng cho [LikeSettings.visitCommentList] và nút «Khôi phục mặc định».
 */
object VisitCommentDefaults {
    val lines: List<String> = listOf(
        "👍",
        "❤️",
        "🔥 Xịn quá",
        "😍 Ưng lắm",
        "👏 Hay quá",
        "💯 Chuẩn luôn",
        "✨ Đẹp ghê",
        "🥰 Dễ thương",
        "😊 Vui quá",
        "💪 Cố lên",
        "🌟 Tuyệt vời",
        "💖 Thích lắm",
        "😄 Hài quá",
        "🙌 Đỉnh đét",
        "🌸 Xinh xỉu",
        "☕ Chill nhé",
        "🎉 Mừng nha",
        "🫶 Yêu quá",
        "😎 Ngầu ghê",
        "🤩 Mê lắm",
        "💐 Đẹp quá",
        "🌈 Màu xịn",
        "🍀 Chúc vui",
        "✌️ Okela luôn",
        "👀 Hay ho",
        "🤗 Thương quá",
        "💫 Cảm ơn",
        "🎯 Trúng tim",
        "📸 Góc đẹp",
        "🎶 Chill ghê"
    )

    fun multilineText(): String = lines.joinToString("\n")
}
