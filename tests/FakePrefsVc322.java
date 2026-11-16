package com.senzwh.deepharness;
import java.util.Map;
import java.util.Set;
public class FakePrefsVc322 implements android.content.SharedPreferences {
    private final Map<String, Object> map;
    public FakePrefsVc322(Map<String, Object> map) { this.map = map; }
    @Override public Map<String, ?> getAll() { return new java.util.HashMap<>(map); }
    @Override public String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
    @Override public Set<String> getStringSet(String k, Set<String> d) { Object v = map.get(k); return v instanceof Set ? (Set<String>) v : d; }
    @Override public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
    @Override public long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
    @Override public float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
    @Override public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    @Override public boolean contains(String k) { return map.containsKey(k); }
    @Override public Editor edit() { return new FakePrefsEditorVc322(map); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    static class FakePrefsEditorVc322 implements Editor {
        private final Map<String, Object> map;
        FakePrefsEditorVc322(Map<String, Object> map) { this.map = map; }
        @Override public Editor putString(String k, String v) { map.put(k, v); return this; }
        @Override public Editor putStringSet(String k, Set<String> v) { map.put(k, v); return this; }
        @Override public Editor putInt(String k, int v) { map.put(k, v); return this; }
        @Override public Editor putLong(String k, long v) { map.put(k, v); return this; }
        @Override public Editor putFloat(String k, float v) { map.put(k, v); return this; }
        @Override public Editor putBoolean(String k, boolean v) { map.put(k, v); return this; }
        @Override public Editor remove(String k) { map.remove(k); return this; }
        @Override public Editor clear() { map.clear(); return this; }
        @Override public boolean commit() { return true; }
        @Override public void apply() {}
    }
}
