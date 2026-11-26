# R8 full mode: giữ tối thiểu metadata cần thiết cho Android/Kotlin.
-allowaccessmodification
-repackageclasses
-renamesourcefileattribute SourceFile
-keepattributes !LineNumberTable,!SourceFile,Signature,InnerClasses,EnclosingMethod,*Annotation

# Loại bỏ lời gọi Android Log khỏi bản Release.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}

# Thành phần được khai báo trong Manifest được Android/R8 giữ theo entry point.
# Không giữ nguyên toàn bộ package vn.hiep.motonav để tránh vô hiệu hoá obfuscation.
