package dev.s200.cozmo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Local SQLite database for Cozmo's social memory (registered people, encounter/interaction counts,
 * familiarity 0.0..1.0, last mood, visual cues) and persistent Nurture Needs (Energy, Play, Comfort).
 * Includes idempotent schema migration via PRAGMA table_info.
 */
public final class MemoryDb extends SQLiteOpenHelper {
    public static final class Person {
        public final long id;
        public final String name;
        public final int encounters;
        public final int interactionCount;
        public final float familiarity;
        public final String lastMood;
        public final long firstSeenMs;
        public final long lastSeenMs;
        public final float lastEyeDist;
        public final float lastLuma;

        Person(long id, String name, int encounters, int interactionCount, float familiarity, String lastMood,
               long firstSeenMs, long lastSeenMs, float lastEyeDist, float lastLuma) {
            this.id = id;
            this.name = name;
            this.encounters = encounters;
            this.interactionCount = interactionCount;
            this.familiarity = familiarity;
            this.lastMood = lastMood == null ? "NEUTRAL" : lastMood;
            this.firstSeenMs = firstSeenMs;
            this.lastSeenMs = lastSeenMs;
            this.lastEyeDist = lastEyeDist;
            this.lastLuma = lastLuma;
        }
    }

    public static final class SavedNeeds {
        public final float energy, play, comfort;
        public final long updatedMs;

        public SavedNeeds(float energy, float play, float comfort, long updatedMs) {
            this.energy = energy;
            this.play = play;
            this.comfort = comfort;
            this.updatedMs = updatedMs;
        }
    }

    public MemoryDb(Context context) {
        super(context, "cozmo_memory.db", null, 2);
        ensureSchema(getWritableDatabase());
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS persons (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "name TEXT NOT NULL UNIQUE," +
            "encounters INTEGER NOT NULL DEFAULT 1," +
            "interaction_count INTEGER NOT NULL DEFAULT 1," +
            "familiarity REAL NOT NULL DEFAULT 0.25," +
            "last_mood TEXT NOT NULL DEFAULT 'NEUTRAL'," +
            "first_seen_ms INTEGER NOT NULL," +
            "last_seen_ms INTEGER NOT NULL," +
            "last_eye_dist REAL NOT NULL DEFAULT 0," +
            "last_luma REAL NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS episodic_events (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "timestamp_ms INTEGER NOT NULL," +
            "kind TEXT NOT NULL," +
            "detail TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS needs_state (" +
            "id INTEGER PRIMARY KEY CHECK (id = 1)," +
            "energy REAL NOT NULL," +
            "play REAL NOT NULL," +
            "comfort REAL NOT NULL," +
            "updated_ms INTEGER NOT NULL)");
        ensureSchema(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    private void ensureSchema(SQLiteDatabase db) {
        Set<String> cols = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(persons)", null)) {
            while (c.moveToNext()) {
                cols.add(c.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        if (!cols.contains("familiarity")) {
            db.execSQL("ALTER TABLE persons ADD COLUMN familiarity REAL NOT NULL DEFAULT 0.25");
        }
        if (!cols.contains("interaction_count")) {
            db.execSQL("ALTER TABLE persons ADD COLUMN interaction_count INTEGER NOT NULL DEFAULT 1");
        }
        if (!cols.contains("last_mood")) {
            db.execSQL("ALTER TABLE persons ADD COLUMN last_mood TEXT NOT NULL DEFAULT 'NEUTRAL'");
        }
    }

    public synchronized Person registerOrMeet(String rawName, float eyeDist, float luma) {
        return registerOrMeet(rawName, eyeDist, luma, "HAPPY");
    }

    public synchronized Person registerOrMeet(String rawName, float eyeDist, float luma, String mood) {
        if (rawName == null) return null;
        String name = rawName.trim();
        if (name.isEmpty()) return null;
        if (name.length() > 36) name = name.substring(0, 36);
        String cleanMood = (mood == null || mood.isEmpty()) ? "HAPPY" : mood;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.query("persons",
                new String[]{"id", "encounters", "interaction_count", "familiarity", "first_seen_ms"},
                "LOWER(name) = LOWER(?)", new String[]{name}, null, null, null)) {
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                int count = c.getInt(1) + 1;
                int inter = c.getInt(2) + 1;
                float fam = Math.min(1.0f, Math.max(0.25f, c.getFloat(3) + 0.14f));
                long first = c.getLong(4);
                ContentValues cv = new ContentValues();
                cv.put("encounters", count);
                cv.put("interaction_count", inter);
                cv.put("familiarity", fam);
                cv.put("last_mood", cleanMood);
                cv.put("last_seen_ms", now);
                if (eyeDist > 0) cv.put("last_eye_dist", eyeDist);
                if (luma >= 0) cv.put("last_luma", luma);
                db.update("persons", cv, "id = ?", new String[]{String.valueOf(id)});
                recordEvent("incontro", "Riconosciuto/incontrato " + name + " (#" + count + ", fam " + Math.round(fam * 100f) + "%)");
                return new Person(id, name, count, inter, fam, cleanMood, first, now, eyeDist, luma);
            }
        }
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("encounters", 1);
        cv.put("interaction_count", 1);
        cv.put("familiarity", 0.25f);
        cv.put("last_mood", cleanMood);
        cv.put("first_seen_ms", now);
        cv.put("last_seen_ms", now);
        cv.put("last_eye_dist", Math.max(0f, eyeDist));
        cv.put("last_luma", Math.max(0f, luma));
        long id = db.insert("persons", null, cv);
        recordEvent("registrazione", "Nuova persona registrata: " + name);
        return new Person(id, name, 1, 1, 0.25f, cleanMood, now, now, eyeDist, luma);
    }

    public synchronized Person recordPersonInteraction(String rawName, String eventType, String mood) {
        if (rawName == null || rawName.trim().isEmpty()) return null;
        String name = rawName.trim();
        String ev = (eventType == null || eventType.isEmpty()) ? "interazione" : eventType;
        String cleanMood = (mood == null || mood.isEmpty()) ? "HAPPY" : mood;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.query("persons",
                new String[]{"id", "encounters", "interaction_count", "familiarity", "first_seen_ms", "last_eye_dist", "last_luma"},
                "LOWER(name) = LOWER(?)", new String[]{name}, null, null, null)) {
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                int enc = c.getInt(1);
                int inter = c.getInt(2) + 1;
                float fam = Math.min(1.0f, c.getFloat(3) + 0.12f);
                long first = c.getLong(4);
                float eyeD = c.getFloat(5);
                float luma = c.getFloat(6);
                ContentValues cv = new ContentValues();
                cv.put("interaction_count", inter);
                cv.put("familiarity", fam);
                cv.put("last_mood", cleanMood);
                cv.put("last_seen_ms", now);
                db.update("persons", cv, "id = ?", new String[]{String.valueOf(id)});
                recordEvent(ev, name + " · " + ev + " (" + cleanMood + ", fam " + Math.round(fam * 100f) + "%)");
                return new Person(id, name, enc, inter, fam, cleanMood, first, now, eyeD, luma);
            }
        }
        return registerOrMeet(name, 0f, -1f, cleanMood);
    }

    public synchronized List<Person> listPersons() {
        List<Person> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("persons",
                new String[]{"id", "name", "encounters", "interaction_count", "familiarity", "last_mood",
                             "first_seen_ms", "last_seen_ms", "last_eye_dist", "last_luma"},
                null, null, null, null, "last_seen_ms DESC")) {
            while (c.moveToNext()) {
                list.add(new Person(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3),
                    c.getFloat(4), c.getString(5), c.getLong(6), c.getLong(7), c.getFloat(8), c.getFloat(9)));
            }
        }
        return list;
    }

    public synchronized Person mostRecentPerson() {
        List<Person> p = listPersons();
        return p.isEmpty() ? null : p.get(0);
    }

    public synchronized String mostRecentPersonName() {
        Person p = mostRecentPerson();
        return p == null ? null : p.name;
    }

    public synchronized List<String> recentEpisodicEvents(int limit) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("episodic_events", new String[]{"detail"}, null, null, null, null, "id DESC", String.valueOf(Math.max(1, limit)))) {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        }
        return out;
    }

    public synchronized void clearAllPeopleAndEvents() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("persons", null, null);
        db.delete("episodic_events", null, null);
    }

    public synchronized void recordEvent(String kind, String detail) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("timestamp_ms", System.currentTimeMillis());
        cv.put("kind", kind == null ? "evento" : kind);
        cv.put("detail", detail == null ? "" : detail);
        db.insert("episodic_events", null, cv);
        db.execSQL("DELETE FROM episodic_events WHERE id NOT IN (SELECT id FROM episodic_events ORDER BY id DESC LIMIT 100)");
    }

    public synchronized void saveNeeds(float energy, float play, float comfort) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("id", 1);
        cv.put("energy", energy);
        cv.put("play", play);
        cv.put("comfort", comfort);
        cv.put("updated_ms", System.currentTimeMillis());
        db.insertWithOnConflict("needs_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized SavedNeeds loadNeedsWithTimestamp() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("needs_state", new String[]{"energy", "play", "comfort", "updated_ms"}, "id = 1", null, null, null, null)) {
            if (c.moveToFirst()) {
                return new SavedNeeds(c.getFloat(0), c.getFloat(1), c.getFloat(2), c.getLong(3));
            }
        }
        return null;
    }

    public synchronized float[] loadNeeds() {
        SavedNeeds sn = loadNeedsWithTimestamp();
        return sn == null ? null : new float[]{sn.energy, sn.play, sn.comfort};
    }

    public synchronized String summary() {
        List<Person> people = listPersons();
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.ITALY);
        StringBuilder sb = new StringBuilder();
        if (people.isEmpty()) {
            sb.append("Persone registrate: nessuna (inserisci un nome per registrare)");
        } else {
            sb.append("Persone ricordate (").append(people.size()).append("):\n");
            for (int i = 0; i < Math.min(5, people.size()); i++) {
                Person p = people.get(i);
                sb.append("• ").append(p.name)
                  .append(String.format(Locale.ITALY, " — fam. %.0f%% · %d %s (%s, %s)",
                      p.familiarity * 100f,
                      p.encounters, p.encounters == 1 ? "incontro" : "incontri",
                      p.lastMood, fmt.format(new Date(p.lastSeenMs))))
                  .append('\n');
            }
        }
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("episodic_events", new String[]{"timestamp_ms", "detail"}, null, null, null, null, "id DESC", "3")) {
            if (c.moveToFirst()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append("Ultimi ricordi:");
                do {
                    sb.append("\n· ").append(fmt.format(new Date(c.getLong(0)))).append(" ").append(c.getString(1));
                } while (c.moveToNext());
            }
        }
        return sb.toString().trim();
    }
}
