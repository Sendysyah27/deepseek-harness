package android.content;
import java.io.File;
/** Stub Context NON-LEMPAR untuk tes JVM: konstruktor android.jar
 * melempar RuntimeException("Stub!") sehingga subclass Context apa
 * pun (mis. FakeCtxVc322) tidak bisa di-instantiate di JVM. Stub ini
 * HARUS didahulukan di classpath run (sebelum android.jar) — hanya
 * untuk tes, TIDAK PERNAH masuk APK. */
public abstract class Context {
    public Context() {}
    public Context getApplicationContext() { return this; }
    public File getDatabasePath(String name) { return null; }
    public File getFilesDir() { return null; }
    public File getCacheDir() { return null; }
    public File getDataDir() { return null; }
    public String getPackageName() { return ""; }
    public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
    public android.content.SharedPreferences getSharedPreferences(String n, int m) { return null; }
    public android.content.res.AssetManager getAssets() { return null; }
    public android.content.res.Resources getResources() { return null; }
    public android.content.pm.PackageManager getPackageManager() { return null; }
    public android.content.ContentResolver getContentResolver() { return null; }
    public android.os.Looper getMainLooper() { return null; }
    public void setTheme(int p0) {}
    public android.content.res.Resources.Theme getTheme() { return null; }
    public android.content.pm.ApplicationInfo getApplicationInfo() { return null; }
    public java.lang.String getPackageResourcePath() { return ""; }
    public java.lang.String getPackageCodePath() { return ""; }
    public boolean moveSharedPreferencesFrom(android.content.Context p0, java.lang.String p1) { return false; }
    public boolean deleteSharedPreferences(java.lang.String p0) { return false; }
    public java.io.FileInputStream openFileInput(java.lang.String p0) { return null; }
    public java.io.FileOutputStream openFileOutput(java.lang.String p0, int p1) { return null; }
    public boolean deleteFile(java.lang.String p0) { return false; }
    public java.io.File getFileStreamPath(java.lang.String p0) { return null; }
    public java.io.File getNoBackupFilesDir() { return null; }
    public java.io.File getExternalFilesDir(java.lang.String p0) { return null; }
    public java.io.File[] getExternalFilesDirs(java.lang.String p0) { return null; }
    public java.io.File getObbDir() { return null; }
    public java.io.File[] getObbDirs() { return null; }
    public java.io.File getCodeCacheDir() { return null; }
    public java.io.File getExternalCacheDir() { return null; }
    public java.io.File[] getExternalCacheDirs() { return null; }
    public java.io.File[] getExternalMediaDirs() { return null; }
    public java.lang.String[] fileList() { return null; }
    public java.io.File getDir(java.lang.String p0, int p1) { return null; }
    public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(java.lang.String p0, int p1, android.database.sqlite.SQLiteDatabase.CursorFactory p2) { return null; }
    public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(java.lang.String p0, int p1, android.database.sqlite.SQLiteDatabase.CursorFactory p2, android.database.DatabaseErrorHandler p3) { return null; }
    public boolean moveDatabaseFrom(android.content.Context p0, java.lang.String p1) { return false; }
    public boolean deleteDatabase(java.lang.String p0) { return false; }
    public java.lang.String[] databaseList() { return null; }
    public android.graphics.drawable.Drawable getWallpaper() { return null; }
    public android.graphics.drawable.Drawable peekWallpaper() { return null; }
    public int getWallpaperDesiredMinimumWidth() { return 0; }
    public int getWallpaperDesiredMinimumHeight() { return 0; }
    public void setWallpaper(android.graphics.Bitmap p0) {}
    public void setWallpaper(java.io.InputStream p0) {}
    public void clearWallpaper() {}
    public void startActivity(android.content.Intent p0) {}
    public void startActivity(android.content.Intent p0, android.os.Bundle p1) {}
    public void startActivities(android.content.Intent[] p0) {}
    public void startActivities(android.content.Intent[] p0, android.os.Bundle p1) {}
    public void startIntentSender(android.content.IntentSender p0, android.content.Intent p1, int p2, int p3, int p4) {}
    public void startIntentSender(android.content.IntentSender p0, android.content.Intent p1, int p2, int p3, int p4, android.os.Bundle p5) {}
    public void sendBroadcast(android.content.Intent p0) {}
    public void sendBroadcast(android.content.Intent p0, java.lang.String p1) {}
    public void sendOrderedBroadcast(android.content.Intent p0, java.lang.String p1) {}
    public void sendOrderedBroadcast(android.content.Intent p0, java.lang.String p1, android.content.BroadcastReceiver p2, android.os.Handler p3, int p4, java.lang.String p5, android.os.Bundle p6) {}
    public void sendBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) {}
    public void sendBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, java.lang.String p2) {}
    public void sendOrderedBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, java.lang.String p2, android.content.BroadcastReceiver p3, android.os.Handler p4, int p5, java.lang.String p6, android.os.Bundle p7) {}
    public void sendStickyBroadcast(android.content.Intent p0) {}
    public void sendStickyOrderedBroadcast(android.content.Intent p0, android.content.BroadcastReceiver p1, android.os.Handler p2, int p3, java.lang.String p4, android.os.Bundle p5) {}
    public void removeStickyBroadcast(android.content.Intent p0) {}
    public void sendStickyBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) {}
    public void sendStickyOrderedBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, android.content.BroadcastReceiver p2, android.os.Handler p3, int p4, java.lang.String p5, android.os.Bundle p6) {}
    public void removeStickyBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) {}
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1) { return null; }
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, int p2) { return null; }
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, java.lang.String p2, android.os.Handler p3) { return null; }
    public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, java.lang.String p2, android.os.Handler p3, int p4) { return null; }
    public void unregisterReceiver(android.content.BroadcastReceiver p0) {}
    public android.content.ComponentName startService(android.content.Intent p0) { return null; }
    public android.content.ComponentName startForegroundService(android.content.Intent p0) { return null; }
    public boolean stopService(android.content.Intent p0) { return false; }
    public boolean bindService(android.content.Intent p0, android.content.ServiceConnection p1, int p2) { return false; }
    public void unbindService(android.content.ServiceConnection p0) {}
    public boolean startInstrumentation(android.content.ComponentName p0, java.lang.String p1, android.os.Bundle p2) { return false; }
    public java.lang.Object getSystemService(java.lang.String p0) { return null; }
    public java.lang.String getSystemServiceName(java.lang.Class<?> p0) { return ""; }
    public int checkPermission(java.lang.String p0, int p1, int p2) { return 0; }
    public int checkCallingPermission(java.lang.String p0) { return 0; }
    public int checkCallingOrSelfPermission(java.lang.String p0) { return 0; }
    public int checkSelfPermission(java.lang.String p0) { return 0; }
    public void enforcePermission(java.lang.String p0, int p1, int p2, java.lang.String p3) {}
    public void enforceCallingPermission(java.lang.String p0, java.lang.String p1) {}
    public void enforceCallingOrSelfPermission(java.lang.String p0, java.lang.String p1) {}
    public void grantUriPermission(java.lang.String p0, android.net.Uri p1, int p2) {}
    public void revokeUriPermission(android.net.Uri p0, int p1) {}
    public void revokeUriPermission(java.lang.String p0, android.net.Uri p1, int p2) {}
    public int checkUriPermission(android.net.Uri p0, int p1, int p2, int p3) { return 0; }
    public int checkCallingUriPermission(android.net.Uri p0, int p1) { return 0; }
    public int checkCallingOrSelfUriPermission(android.net.Uri p0, int p1) { return 0; }
    public int checkUriPermission(android.net.Uri p0, java.lang.String p1, java.lang.String p2, int p3, int p4, int p5) { return 0; }
    public void enforceUriPermission(android.net.Uri p0, int p1, int p2, int p3, java.lang.String p4) {}
    public void enforceCallingUriPermission(android.net.Uri p0, int p1, java.lang.String p2) {}
    public void enforceCallingOrSelfUriPermission(android.net.Uri p0, int p1, java.lang.String p2) {}
    public void enforceUriPermission(android.net.Uri p0, java.lang.String p1, java.lang.String p2, int p3, int p4, int p5, java.lang.String p6) {}
    public android.content.Context createPackageContext(java.lang.String p0, int p1) { return null; }
    public android.content.Context createContextForSplit(java.lang.String p0) { return null; }
    public android.content.Context createConfigurationContext(android.content.res.Configuration p0) { return null; }
    public android.content.Context createDisplayContext(android.view.Display p0) { return null; }
    public android.content.Context createDeviceProtectedStorageContext() { return null; }
    public boolean isDeviceProtectedStorage() { return false; }
}
