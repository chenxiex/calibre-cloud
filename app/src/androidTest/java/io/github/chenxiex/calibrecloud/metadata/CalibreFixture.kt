package io.github.chenxiex.calibrecloud.metadata

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

/** Minimal capability fixture checked against Calibre 9.14.0 db/backend.py and schema.sql. */
object CalibreFixture {
    fun create(file: File, libraryUuid: UUID = UUID.randomUUID(), bookUuid: UUID = UUID.randomUUID(),
        boolLabel: String = "finished", boolValue: Boolean? = true, bookPath: String = "作者/书名 (1)",
        lastModified: String? = null, epubSize: Long = 42): File {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            listOf(
                "books(id INTEGER PRIMARY KEY,uuid TEXT,title TEXT,timestamp TEXT,path TEXT,series_index REAL,has_cover INTEGER)",
                "authors(id INTEGER PRIMARY KEY,name TEXT)", "books_authors_link(id INTEGER PRIMARY KEY,book INTEGER,author INTEGER)",
                "tags(id INTEGER PRIMARY KEY,name TEXT)", "books_tags_link(id INTEGER PRIMARY KEY,book INTEGER,tag INTEGER)",
                "series(id INTEGER PRIMARY KEY,name TEXT)", "books_series_link(id INTEGER PRIMARY KEY,book INTEGER,series INTEGER)",
                "ratings(id INTEGER PRIMARY KEY,rating INTEGER)", "books_ratings_link(id INTEGER PRIMARY KEY,book INTEGER,rating INTEGER)",
                "comments(book INTEGER,text TEXT)", "data(book INTEGER,format TEXT,uncompressed_size INTEGER,name TEXT)",
                "library_id(uuid TEXT)",
                "custom_columns(id INTEGER PRIMARY KEY,label TEXT,name TEXT,datatype TEXT,is_multiple INTEGER,normalized INTEGER,mark_for_delete INTEGER)",
                "custom_column_1(id INTEGER PRIMARY KEY,book INTEGER,value BOOL)",
                "custom_column_2(id INTEGER PRIMARY KEY,value TEXT)", "books_custom_column_2_link(id INTEGER PRIMARY KEY,book INTEGER,value INTEGER)",
                "custom_column_3(id INTEGER PRIMARY KEY,value TEXT)", "books_custom_column_3_link(id INTEGER PRIMARY KEY,book INTEGER,value INTEGER)",
                "custom_column_4(id INTEGER PRIMARY KEY,value TEXT)", "books_custom_column_4_link(id INTEGER PRIMARY KEY,book INTEGER,value INTEGER)",
            ).forEach { db.execSQL("CREATE TABLE $it") }
            db.execSQL("INSERT INTO library_id VALUES (?)", arrayOf(libraryUuid.toString()))
            db.execSQL("INSERT INTO books(id,uuid,title,timestamp,path,series_index,has_cover) VALUES (1,?,'示例书','2026-01-02 03:04:05+00:00',?,2.5,1)", arrayOf(bookUuid.toString(), bookPath))
            // Optional: books without last_modified stand for Calibre schemas the parser must still accept.
            if (lastModified != null) {
                db.execSQL("ALTER TABLE books ADD COLUMN last_modified TEXT")
                db.execSQL("UPDATE books SET last_modified = ? WHERE id = 1", arrayOf(lastModified))
            }
            db.execSQL("INSERT INTO data VALUES(1,'EPUB',?,'正文'),(1,'PDF',NULL,'正文')", arrayOf(epubSize))
            db.execSQL("INSERT INTO custom_columns VALUES(1,?,'读完','bool',0,0,0)", arrayOf(boolLabel))
            if (boolValue != null) db.execSQL("INSERT INTO custom_column_1 VALUES(1,1,?)", arrayOf(if (boolValue) 1 else 0))
            listOf("INSERT INTO authors VALUES(1,'作者|姓名')", "INSERT INTO books_authors_link VALUES(1,1,1)",
                "INSERT INTO tags VALUES(1,'标签甲'),(2,'标签乙')", "INSERT INTO books_tags_link VALUES(1,1,1),(2,1,2)",
                "INSERT INTO series VALUES(1,'丛书')", "INSERT INTO books_series_link VALUES(1,1,1)",
                "INSERT INTO ratings VALUES(1,8)", "INSERT INTO books_ratings_link VALUES(1,1,1)",
                "INSERT INTO comments VALUES(1,'<p>说明 &amp; 简介</p><script>hidden</script>')",
                "INSERT INTO custom_columns VALUES(2,'topic','主题','text',0,1,0),(3,'subjects','主题集','text',1,1,0),(4,'shelf','书架','enumeration',0,1,0),(5,'formula','计算','composite',0,0,0)",
                "INSERT INTO custom_column_2 VALUES(1,'单值')", "INSERT INTO books_custom_column_2_link VALUES(1,1,1)",
                "INSERT INTO custom_column_3 VALUES(1,'甲'),(2,'乙')", "INSERT INTO books_custom_column_3_link VALUES(1,1,1),(2,1,2)",
                "INSERT INTO custom_column_4 VALUES(1,'收藏')", "INSERT INTO books_custom_column_4_link VALUES(1,1,1)",
            ).forEach(db::execSQL)
        }
        return file
    }
}
