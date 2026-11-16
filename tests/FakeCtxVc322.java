package com.senzwh.deepharness;
import android.content.Context;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
/** Context palsu untuk tes JVM (dihasilkan dari javap). */
public class FakeCtxVc322 extends Context {
    public final File dir;
    public final Map<String, Object> prefsMap = new HashMap<>();
    public FakeCtxVc322(File dir) { this.dir = dir; }
    @Override public Context getApplicationContext() { return this; }
    @Override public File getDatabasePath(String name) { return new File(dir, name); }
    @Override public File getFilesDir() { return dir; }
    @Override public File getCacheDir() { return dir; }
    @Override public File getDataDir() { return dir; }
    @Override public String getPackageName() { return "com.senzwh.deepharness"; }
    @Override public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
    @Override public android.content.SharedPreferences getSharedPreferences(String n, int m) { return new FakePrefsVc322(prefsMap); }
    @Override public android.content.res.AssetManager getAssets() { return null; }
    @Override public android.content.res.Resources getResources() { return null; }
    @Override public android.content.pm.PackageManager getPackageManager() { return null; }
    @Override public android.content.ContentResolver getContentResolver() { return null; }
    @Override public android.os.Looper getMainLooper() { return null; }
    @Override public void setTheme(int p0) {}
    @Override public android.content.res.Resources.Theme getTheme() { return null; }
    @Override public android.content.pm.ApplicationInfo getApplicationInfo() { return null; }
    @Override public java.lang.String getPackageResourcePath() { return ""; }
    @Override public java.lang.String getPackageCodePath() { return ""; }
    @Override public boolean moveSharedPreferencesFrom(android.content.Context p0, java.lang.String p1) { return false; }
    @Override public boolean deleteSharedPreferences(java.lang.String p0) { return false; }
    @Override public java.io.FileInputStream openFileInput(java.lang.String p0) { return null; }
    @Override public java.io.FileOutputStream openFileOutput(java.lang.String p0, int p1) { return null; }
    @Override public boolean deleteFile(java.lang.String p0) { return false; }
    @Override public java.io.File getFileStreamPath(java.lang.String p0) { return null; }
    @Override public java.io.File getNoBackupFilesDir() { return null; }
    @Override public java.io.File getExternalFilesDir(java.lang.String p0) { return null; }
    @Override public java.io.File[] getExternalFilesDirs(java.lang.String p0) { return null; }
    @Override public java.io.File getObbDir() { return null; }
    @Override public java.io.File[] getObbDirs() { return null; }
    @Override public java.io.File getCodeCacheDir() { return null; }
    @Override public java.io.File getExternalCacheDir() { return null; }
    @Override public java.io.File[] getExternalCacheDirs() { return null; }
    @Override public java.io.File[] getExternalMediaDirs() { return null; }
    @Override public java.lang.String[] fileList() { return null; }
    @Override public java.io.File getDir(java.lang.String p0, int p1) { return null; }
    @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(java.lang.String p0, int p1, android.database.sqlite.SQLiteDatabase.CursorFactory p2) { return null; }
    @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(java.lang.String p0, int p1, android.database.sqlite.SQLiteDatabase.CursorFactory p2, android.database.DatabaseErrorHandler p3) { return null; }
    @Override public boolean moveDatabaseFrom(android.content.Context p0, java.lang.String p1) { return false; }
    @Override public boolean deleteDatabase(java.lang.String p0) { return false; }
    @Override public java.lang.String[] databaseList() { return null; }
    @Override public android.graphics.drawable.Drawable getWallpaper() { return null; }
    @Override public android.graphics.drawable.Drawable peekWallpaper() { return null; }
    @Override public int getWallpaperDesiredMinimumWidth() { return 0; }
    @Override public int getWallpaperDesiredMinimumHeight() { return 0; }
    @Override public void setWallpaper(android.graphics.Bitmap p0) {}
    @Override public void setWallpaper(java.io.InputStream p0) {}
    @Override public void clearWallpaper() {}
    @Override public void startActivity(android.content.Intent p0) {}
    @Override public void startActivity(android.content.Intent p0, android.os.Bundle p1) {}
    @Override public void startActivities(android.content.Intent[] p0) {}
    @Override public void startActivities(android.content.Intent[] p0, android.os.Bundle p1) {}
    @Override public void startIntentSender(android.content.IntentSender p0, android.content.Intent p1, int p2, int p3, int p4) {}
    @Override public void startIntentSender(android.content.IntentSender p0, android.content.Intent p1, int p2, int p3, int p4, android.os.Bundle p5) {}
    @Override public void sendBroadcast(android.content.Intent p0) {}
    @Override public void sendBroadcast(android.content.Intent p0, java.lang.String p1) {}
    @Override public void sendOrderedBroadcast(android.content.Intent p0, java.lang.String p1) {}
    @Override public void sendOrderedBroadcast(android.content.Intent p0, java.lang.String p1, android.content.BroadcastReceiver p2, android.os.Handler p3, int p4, java.lang.String p5, android.os.Bundle p6) {}
    @Override public void sendBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) {}
    @Override public void sendBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, java.lang.String p2) {}
    @Override public void sendOrderedBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, java.lang.String p2, android.content.BroadcastReceiver p3, android.os.Handler p4, int p5, java.lang.String p6, android.os.Bundle p7) {}
    @Override public void sendStickyBroadcast(android.content.Intent p0) {}
    @Override public void sendStickyOrderedBroadcast(android.content.Intent p0, android.content.BroadcastReceiver p1, android.os.Handler p2, int p3, java.lang.String p4, android.os.Bundle p5) {}
    @Override public void removeStickyBroadcast(android.content.Intent p0) {}
    @Override public void sendStickyBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) {}
    @Override public void sendStickyOrderedBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1, android.content.BroadcastReceiver p2, android.os.Handler p3, int p4, java.lang.String p5, android.os.Bundle p6) {}
    @Override public void removeStickyBroadcastAsUser(android.content.Intent p0, android.os.UserHandle p1) {}
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1) { return null; }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, int p2) { return null; }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, java.lang.String p2, android.os.Handler p3) { return null; }
    @Override public android.content.Intent registerReceiver(android.content.BroadcastReceiver p0, android.content.IntentFilter p1, java.lang.String p2, android.os.Handler p3, int p4) { return null; }
    @Override public void unregisterReceiver(android.content.BroadcastReceiver p0) {}
    @Override public android.content.ComponentName startService(android.content.Intent p0) { return null; }
    @Override public android.content.ComponentName startForegroundService(android.content.Intent p0) { return null; }
    @Override public boolean stopService(android.content.Intent p0) { return false; }
    @Override public boolean bindService(android.content.Intent p0, android.content.ServiceConnection p1, int p2) { return false; }
    @Override public void unbindService(android.content.ServiceConnection p0) {}
    @Override public boolean startInstrumentation(android.content.ComponentName p0, java.lang.String p1, android.os.Bundle p2) { return false; }
    @Override public java.lang.Object getSystemService(java.lang.String p0) { return null; }
    @Override public java.lang.String getSystemServiceName(java.lang.Class<?> p0) { return ""; }
    @Override public int checkPermission(java.lang.String p0, int p1, int p2) { return 0; }
    @Override public int checkCallingPermission(java.lang.String p0) { return 0; }
    @Override public int checkCallingOrSelfPermission(java.lang.String p0) { return 0; }
    @Override public int checkSelfPermission(java.lang.String p0) { return 0; }
    @Override public void enforcePermission(java.lang.String p0, int p1, int p2, java.lang.String p3) {}
    @Override public void enforceCallingPermission(java.lang.String p0, java.lang.String p1) {}
    @Override public void enforceCallingOrSelfPermission(java.lang.String p0, java.lang.String p1) {}
    @Override public void grantUriPermission(java.lang.String p0, android.net.Uri p1, int p2) {}
    @Override public void revokeUriPermission(android.net.Uri p0, int p1) {}
    @Override public void revokeUriPermission(java.lang.String p0, android.net.Uri p1, int p2) {}
    @Override public int checkUriPermission(android.net.Uri p0, int p1, int p2, int p3) { return 0; }
    @Override public int checkCallingUriPermission(android.net.Uri p0, int p1) { return 0; }
    @Override public int checkCallingOrSelfUriPermission(android.net.Uri p0, int p1) { return 0; }
    @Override public int checkUriPermission(android.net.Uri p0, java.lang.String p1, java.lang.String p2, int p3, int p4, int p5) { return 0; }
    @Override public void enforceUriPermission(android.net.Uri p0, int p1, int p2, int p3, java.lang.String p4) {}
    @Override public void enforceCallingUriPermission(android.net.Uri p0, int p1, java.lang.String p2) {}
    @Override public void enforceCallingOrSelfUriPermission(android.net.Uri p0, int p1, java.lang.String p2) {}
    @Override public void enforceUriPermission(android.net.Uri p0, java.lang.String p1, java.lang.String p2, int p3, int p4, int p5, java.lang.String p6) {}
    @Override public android.content.Context createPackageContext(java.lang.String p0, int p1) { return null; }
    @Override public android.content.Context createContextForSplit(java.lang.String p0) { return null; }
    @Override public android.content.Context createConfigurationContext(android.content.res.Configuration p0) { return null; }
    @Override public android.content.Context createDisplayContext(android.view.Display p0) { return null; }
    @Override public android.content.Context createDeviceProtectedStorageContext() { return null; }
    @Override public boolean isDeviceProtectedStorage() { return false; }
}
