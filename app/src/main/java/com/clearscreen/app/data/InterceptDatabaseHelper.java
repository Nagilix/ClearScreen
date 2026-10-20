package com.clearscreen.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class InterceptDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "clearscreen.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_INTERCEPT = "intercept_stats";
    public static final String COL_PKG = "package_name";
    public static final String COL_APP_NAME = "app_name";
    public static final String COL_COUNT = "intercept_count";
    public static final String COL_LAST_TIME = "last_intercept_time";

    private static InterceptDatabaseHelper instance;

    public static synchronized InterceptDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new InterceptDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    private InterceptDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_INTERCEPT + " (" +
                COL_PKG + " TEXT PRIMARY KEY, " +
                COL_APP_NAME + " TEXT, " +
                COL_COUNT + " INTEGER DEFAULT 0, " +
                COL_LAST_TIME + " INTEGER DEFAULT 0)";
        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_INTERCEPT);
        onCreate(db);
    }

    public synchronized void recordIntercept(String packageName, String appName) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor cursor = db.query(TABLE_INTERCEPT, new String[]{COL_COUNT}, COL_PKG + "=?", new String[]{packageName}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int currentCount = cursor.getInt(0);
            cursor.close();

            ContentValues values = new ContentValues();
            values.put(COL_COUNT, currentCount + 1);
            values.put(COL_LAST_TIME, System.currentTimeMillis());
            values.put(COL_APP_NAME, appName);
            db.update(TABLE_INTERCEPT, values, COL_PKG + "=?", new String[]{packageName});
        } else {
            if (cursor != null) cursor.close();
            ContentValues values = new ContentValues();
            values.put(COL_PKG, packageName);
            values.put(COL_APP_NAME, appName);
            values.put(COL_COUNT, 1);
            values.put(COL_LAST_TIME, System.currentTimeMillis());
            db.insert(TABLE_INTERCEPT, null, values);
        }
    }

    public synchronized int getTotalInterceptCount() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT SUM(" + COL_COUNT + ") FROM " + TABLE_INTERCEPT, null);
        int total = 0;
        if (cursor != null && cursor.moveToFirst()) {
            total = cursor.getInt(0);
            cursor.close();
        }
        return total;
    }

    public synchronized void clearAllRecords() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_INTERCEPT, null, null);
    }

    public synchronized void deleteRecord(String packageName) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_INTERCEPT, COL_PKG + "=?", new String[]{packageName});
    }

    public synchronized List<InterceptRecord> getAllRecords() {
        List<InterceptRecord> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_INTERCEPT, null, null, null, null, null, COL_COUNT + " DESC");
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String pkg = cursor.getString(cursor.getColumnIndexOrThrow(COL_PKG));
                String name = cursor.getString(cursor.getColumnIndexOrThrow(COL_APP_NAME));
                int count = cursor.getInt(cursor.getColumnIndexOrThrow(COL_COUNT));
                long time = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_TIME));
                list.add(new InterceptRecord(pkg, name, count, time));
            }
            cursor.close();
        }
        return list;
    }
}
