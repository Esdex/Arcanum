package zip.arcanum.arcanum.gallery.editor.model

enum class CropAspectRatio(val label: String, val ratio: Float?) {
    FREE("Free",  null),
    R1_1("1:1",   1f),
    R5_4("5:4",   5f / 4f),
    R4_3("4:3",   4f / 3f),
    R3_2("3:2",   3f / 2f),
    R16_9("16:9", 16f / 9f),
    R4_5("4:5",   4f / 5f),
    R3_4("3:4",   3f / 4f),
    R2_3("2:3",   2f / 3f),
    R9_16("9:16", 9f / 16f)
}
