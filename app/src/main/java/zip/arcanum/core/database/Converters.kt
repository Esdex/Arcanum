package zip.arcanum.core.database

import androidx.room.TypeConverter
import zip.arcanum.core.database.entities.MediaFileType

class Converters {
    @TypeConverter
    fun fromMediaFileType(type: MediaFileType): String = type.name

    @TypeConverter
    fun toMediaFileType(value: String): MediaFileType = MediaFileType.valueOf(value)
}
