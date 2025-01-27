/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.persistence.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.android.persistence.AppExecutors
import com.example.android.persistence.db.converter.DateConverter
import com.example.android.persistence.db.dao.CommentDao
import com.example.android.persistence.db.dao.ProductDao
import com.example.android.persistence.db.entity.CommentEntity
import com.example.android.persistence.db.entity.ProductEntity
import com.example.android.persistence.db.entity.ProductFtsEntity

@Database(
    entities = [ProductEntity::class, ProductFtsEntity::class, CommentEntity::class],
    version = 2
)
@TypeConverters(
    DateConverter::class
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun commentDao(): CommentDao
    private val mIsDatabaseCreated = MutableLiveData<Boolean>()

    /**
     * Check whether the database already exists and expose it via [.getDatabaseCreated]
     */
    private fun updateDatabaseCreated(context: Context) {
        if (context.getDatabasePath(DATABASE_NAME).exists()) {
            setDatabaseCreated()
        }
    }

    private fun setDatabaseCreated() {
        mIsDatabaseCreated.postValue(true)
    }

    val databaseCreated: LiveData<Boolean>
        get() = mIsDatabaseCreated

    companion object {
        private var sInstance: AppDatabase? = null

        @JvmField
        @VisibleForTesting
        val DATABASE_NAME = "basic-sample-db"
        @JvmStatic
        fun getInstance(context: Context, executors: AppExecutors): AppDatabase? {
            if (sInstance == null) {
                synchronized(AppDatabase::class.java) {
                    if (sInstance == null) {
                        sInstance = buildDatabase(context.applicationContext, executors)
                        sInstance!!.updateDatabaseCreated(context.applicationContext)
                    }
                }
            }
            return sInstance
        }

        /**
         * Build the database. [Builder.build] only sets up the database configuration and
         * creates a new instance of the database.
         * The SQLite database is only created when it's accessed for the first time.
         */
        private fun buildDatabase(
            appContext: Context,
            executors: AppExecutors
        ): AppDatabase {
            return Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        executors.diskIO().execute {

                            // Add a delay to simulate a long-running operation
                            addDelay()
                            // Generate the data for pre-population
                            val database = getInstance(appContext, executors)
                            val products = DataGenerator.generateProducts()
                            val comments = DataGenerator.generateCommentsForProducts(products)
                            insertData(database, products, comments)
                            // notify that the database was created and it's ready to be used
                            database!!.setDatabaseCreated()
                        }
                    }
                })
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        private fun insertData(
            database: AppDatabase?, products: List<ProductEntity>,
            comments: List<CommentEntity>
        ) {
            database!!.runInTransaction {
                database.productDao().insertAll(products)
                database.commentDao().insertAll(comments)
            }
        }

        private fun addDelay() {
            try {
                Thread.sleep(4000)
            } catch (ignored: InterruptedException) {
            }
        }

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `productsFts` USING FTS4("
                            + "`name` TEXT, `description` TEXT, content=`products`)"
                )
                database.execSQL(
                    "INSERT INTO productsFts (`rowid`, `name`, `description`) "
                            + "SELECT `id`, `name`, `description` FROM products"
                )
            }
        }
    }
}