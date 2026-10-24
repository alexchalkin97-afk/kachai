# youtubedl-android парсит JSON через Jackson в эти классы
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class app.kachai.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# commons-compress распаковывает Python и создаёт классы zip-полей через рефлексию
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.compress.**
