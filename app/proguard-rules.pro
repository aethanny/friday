# kotlinx.serialization, OkHttp and Room ship their own R8 rules.
# Keep @Serializable AI payload classes' generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.friday.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
