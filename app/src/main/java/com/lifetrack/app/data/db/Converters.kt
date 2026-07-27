package com.lifetrack.app.data.db

import androidx.room.TypeConverter
import com.lifetrack.app.data.db.entity.CategoryKind
import com.lifetrack.app.data.db.entity.CategorySource
import com.lifetrack.app.data.db.entity.ExclusionSource
import com.lifetrack.app.data.db.entity.TxnSource
import com.lifetrack.app.data.db.entity.TxnType

class Converters {
    @TypeConverter
    fun fromTxnType(value: TxnType) = value.name

    @TypeConverter
    fun toTxnType(value: String) = TxnType.valueOf(value)

    @TypeConverter
    fun fromTxnSource(value: TxnSource) = value.name

    @TypeConverter
    fun toTxnSource(value: String) = TxnSource.valueOf(value)

    @TypeConverter
    fun fromCategoryKind(value: CategoryKind) = value.name

    @TypeConverter
    fun toCategoryKind(value: String) = CategoryKind.valueOf(value)

    @TypeConverter
    fun fromExclusionSource(value: ExclusionSource) = value.name

    @TypeConverter
    fun toExclusionSource(value: String) = ExclusionSource.valueOf(value)

    @TypeConverter
    fun fromCategorySource(value: CategorySource) = value.name

    @TypeConverter
    fun toCategorySource(value: String) = CategorySource.valueOf(value)
}
