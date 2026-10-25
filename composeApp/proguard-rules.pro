# JavaMail (android-mail) yansıma ile sağlayıcı yükler.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.sasl.**
-dontwarn java.beans.**

# Kripto çekirdeği köprüsü: WebView yalnızca bu işaretli yöntemleri çağırır (isimleri korunmalı).
-keepclassmembers class io.maildots.app.e2e.AndroidE2ECrypto$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
