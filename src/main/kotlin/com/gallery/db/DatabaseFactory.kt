package com.gallery.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/**
 * Owns the Postgres connection pool and runs Flyway database migrations on boot.
 */
object DatabaseFactory {

    fun init() {
        val config = HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/gallery"
            username = System.getenv("DB_USER") ?: "gallery_app"
            password = System.getenv("DB_PASSWORD") ?: "gallery890"
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        val dataSource = HikariDataSource(config)

        // Run Flyway database migrations
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true)
            .load()
        flyway.migrate()

        Database.connect(dataSource)
    }
}
